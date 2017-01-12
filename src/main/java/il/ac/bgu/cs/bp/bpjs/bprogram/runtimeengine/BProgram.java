package il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine;

import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.tasks.ResumeBThread;
import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.tasks.StartBThread;
import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.exceptions.BProgramException;
import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.jsproxy.BProgramJsProxy;
import il.ac.bgu.cs.bp.bpjs.events.BEvent;

import java.util.*;
import java.util.concurrent.*;

import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.listeners.BProgramListener;
import il.ac.bgu.cs.bp.bpjs.eventselection.EventSelectionResult;
import il.ac.bgu.cs.bp.bpjs.eventselection.EventSelectionStrategy;
import il.ac.bgu.cs.bp.bpjs.eventselection.SimpleEventSelectionStrategy;
import static il.ac.bgu.cs.bp.bpjs.eventsets.Events.all;
import static il.ac.bgu.cs.bp.bpjs.eventsets.Events.emptySet;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import static java.nio.file.Files.readAllBytes;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.util.stream.Collectors.toList;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.Scriptable;
import static java.util.stream.Collectors.toSet;
import static java.nio.file.Paths.get;
import static java.util.Collections.reverseOrder;
import java.util.concurrent.atomic.AtomicInteger;
import org.mozilla.javascript.ContinuationPending;
import org.mozilla.javascript.WrappedException;

/**
 * Base class for BPrograms. Contains the logic for managing {@link BThreadSyncSnapshot}s
 * and the main event loop. Concrete BProgram extend this class by implementing 
 * the {@link #setupProgramScope(org.mozilla.javascript.Scriptable)} method.
 * 
 * <p>
 * For creating a BProgram that uses a single Javascript file available in the
 * classpath, see {@link SingleResourceBProgram}.
 *
 * @author michael
 */
public abstract class BProgram {
    
    // ------------- Static Members ---------------
    
    /**
     * "Poison pill" to insert to the external event queue. Used only to turn the
     * daemon mode off.
     */
    private static final BEvent NO_MORE_DAEMON = new BEvent("NO_MORE_DAEMON");
    
    /** Counter for giving anonymous instances some semantic name. */
    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    // ------------- Instance Members ---------------
    
    /**
     * Snapshots of participating BThreads, when in bsync point.
     */
    protected Set<BThreadSyncSnapshot> bthreads;
    private Set<BThreadSyncSnapshot> nextRoundBthreads;

    private String name;

    /**
     * When {@code true}, the BProgram waits for an external event when no
     * internal ones are available.
     */
    private boolean daemonMode;
    private final ExecutorService executor = new ForkJoinPool();
    private EventSelectionStrategy eventSelectionStrategy;

    /**
     * Events are enqueued here by external threads
     */
    private final BlockingQueue<BEvent> recentlyEnqueuedExternalEvents = new LinkedBlockingQueue<>();

    /**
     * At the BProgram's leisure, the external event are moved here, where they
     * can be managed.
     */
    private final List<BEvent> enqueuedExternalEvents = new LinkedList<>();

    /**
     * BThreads added between bsyncs are added here.
     */
    private final BlockingQueue<BThreadSyncSnapshot> recentlyRegisteredBthreads = new LinkedBlockingDeque<>();

    private final List<BProgramListener> listeners = new ArrayList<>();

    private volatile boolean started = false;

    protected Scriptable programScope;

    public BProgram() {
        this("BProgram-" + INSTANCE_COUNTER.incrementAndGet());
    }

    public BProgram(String aName) {
        this(aName, new SimpleEventSelectionStrategy());
    }

    public BProgram(String aName, EventSelectionStrategy anEventSelectionStrategy) {
        name = aName;
        bthreads = new HashSet<>();
        eventSelectionStrategy = anEventSelectionStrategy;
    }

    public void start() throws InterruptedException {
        try {
            setup();
            listeners.forEach(l -> l.started(this));
            started = true;
            
            addToNextRound(executor.invokeAll(bthreads.stream()
                    .map(bt -> new StartBThread(bt))
                    .collect(toList())));
            addToNextRound(startRecentlyRegisteredBThreads());
            finalizeRound();
            
            if (bthreads.isEmpty()) {
                // super corner case, where no bsyncs were called.
                listeners.forEach(l -> l.ended(this));
            } else {
                do {
                    mainEventLoop();
                } while (isDaemonMode() && waitForExternalEvent());
                listeners.forEach(l -> l.ended(this));
            }
        } catch ( WrappedException we ) {
            throw new BProgramException(we.getCause());
        }
    }

    /**
     * Advances the BProgram a single super-step, that is until there are no
     * more internal events that can be selected.
     *
     * @throws InterruptedException
     */
    protected void mainEventLoop() throws InterruptedException {
        boolean go = true;
        while (go) {
            // 1. Possibly select an event
            recentlyEnqueuedExternalEvents.drainTo(enqueuedExternalEvents);
            if (enqueuedExternalEvents.remove(NO_MORE_DAEMON)) {
                daemonMode = false;
            }
            Set<BEvent> availableEvents = eventSelectionStrategy.selectableEvents(currentStatements(), enqueuedExternalEvents);
            if ( availableEvents.isEmpty() ) {
                go = false;
            } else {
                Optional<EventSelectionResult> res = eventSelectionStrategy.select(currentStatements(), enqueuedExternalEvents, availableEvents);

                // 2.Trigger the event
                if ( res.isPresent() ) {
                    EventSelectionResult esr = res.get();
                    try {
                        esr.getIndicesToRemove().stream().sorted(reverseOrder())
                                .forEach( idxObj -> enqueuedExternalEvents.remove(idxObj.intValue()) );
                        triggerEvent(esr.getEvent());
                        finalizeRound();
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                    if ( bthreads.isEmpty() ) {
                        go = false; // no more BThreads left
                    }
                } else {
                    go = false;
                }
            }
        }
        listeners.forEach(l -> l.superstepDone(this));
    }
    
    /**
     * Awakens BThreads that waited for/requested this event in their last
     * bsync, and waits for them to terminate.
     *
     * @param selectedEvent The event to trigger. Cannot be {@code null}.
     * @throws java.lang.InterruptedException
     */
    protected void triggerEvent( BEvent selectedEvent) throws InterruptedException {
        if (selectedEvent == null) {
            throw new IllegalArgumentException("Cannot trigger a null event.");
        }
        listeners.forEach(l -> l.eventSelected(this, selectedEvent));

        bthreads.forEach(bt -> {
            if (bt.getBSyncStatement() == null) {
                System.err.println("SEVERE: " + bt.getName() + " Has null stmt");
            }
        });
        
        // We are about to execute Javascript code ////////////////
        Context ctxt = Context.enter();
        
        Set<BThreadSyncSnapshot> brokenUpon = bthreads.stream()
                .filter(bt -> bt.getBSyncStatement().getInterrupt().contains(selectedEvent))
                .collect(toSet());

        // Handle breakUpons
        if (!brokenUpon.isEmpty()) {
            bthreads.removeAll(brokenUpon);
            brokenUpon.forEach(bt -> {
                listeners.forEach(l -> l.bthreadRemoved(this, bt));
                bt.getInterrupt()
                      .ifPresent( func -> {
                          final Scriptable scope = bt.getScope();
                          scope.delete("bsync"); // can't call bsync from a break handler.
                          try {
                              ctxt.callFunctionWithContinuations(func, scope, new Object[]{selectedEvent});
                          } catch ( ContinuationPending ise ) {
                              throw new BProgramException("Cannot call bsync from a break-upon handler. Consider pushing an external event.");
                          }
                      });
            });
        }
        
        
        // See who wakes up for the selected event and how skips this round.
        Set<BThreadSyncSnapshot> resumingThisRound = new HashSet<>(bthreads.size());
        Set<BThreadSyncSnapshot> sleepingThisRound = new HashSet<>(bthreads.size());
        bthreads.forEach( snapshot -> {
            (snapshot.getBSyncStatement().shouldWakeFor(selectedEvent) ? resumingThisRound : sleepingThisRound).add(snapshot);
        });
        
        Context.exit();
        
        // Javascript code done ///////////////////////////////////
        
        // add the run results of all those who advance this stage
        addToNextRound(executor.invokeAll(resumingThisRound.stream()
                .map(bt -> new ResumeBThread(bt, selectedEvent))
                .collect(toList())));
        
        // if any new bthreads are added, run and add them
        addToNextRound(startRecentlyRegisteredBThreads());
        
        // carry over BThreads that did not advance this round to next round.
        nextRoundBthreads.addAll(sleepingThisRound);
    }

    private List<Future<BThreadSyncSnapshot>> startRecentlyRegisteredBThreads() throws InterruptedException {
        
        // Setup the new BThread's scopes.
        try {
            Context cx = ContextFactory.getGlobal().enterContext();
            cx.setOptimizationLevel(-1); // must use interpreter mode
            recentlyRegisteredBthreads.forEach(bt -> setupAddedBThread(bt));
        } finally {
            Context.exit();
        }

        // run the new BThreads.
        final List<Future<BThreadSyncSnapshot>> result = executor.invokeAll(recentlyRegisteredBthreads.stream()
                .map(bt -> new StartBThread(bt))
                .filter( Objects::nonNull )
                .collect(toList()));
        recentlyRegisteredBthreads.clear();
        
        return result;
    }

    /**
     * Loads a Javascript resource (a file that's included in the .jar).
     *
     * @param pathInJar path of the resource, relative to the class.
     */
    public void evaluateResource(String pathInJar) {
        try {
            final URL resource = Thread.currentThread().getContextClassLoader().getResource(pathInJar);
            if (resource == null) {
                throw new RuntimeException("Resource '" + pathInJar + "' not found.");
            }
            evaluateCodeAt(resource.toURI());
        } catch (URISyntaxException ex) {
            Logger.getLogger(BProgram.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
    
    /**
     * Reads and evaluates the code pointed by the URI. The resource has to be
     * local (either in the file system or in the classpath.
     * @param path path to the code to evaluate
     * @return Result of code evaluation.
     */
    protected Object evaluateCodeAt(URI path) {
        Path pathObject = get(path);
        try {
            String script = new String(readAllBytes(pathObject), StandardCharsets.UTF_8);
            return evaluate(script, pathObject.toString());
        } catch (IOException e) {
            throw new RuntimeException("Error while reading code at '" + path + "': " + e.getMessage(), e);
        }
    }
    
    /**
     * Reads and evaluates the code at the passed input stream. The stream is 
     * read to its end, but is not closed.
     * 
     * @param inStrm 
     * @param scriptName for error reporting purposes.
     * @return Result of evaluating the code at {@code inStrm}.
     */
    protected Object evaluate(InputStream inStrm, String scriptName) {
        InputStreamReader streamReader = new InputStreamReader(inStrm, StandardCharsets.UTF_8);
        BufferedReader br = new BufferedReader(streamReader);
        StringBuilder sb = new StringBuilder();
        String line;
        try {
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (IOException e) {
            throw new RuntimeException("error while reading javascript from stream", e);
        }
        String script = sb.toString();
        return evaluate(script, scriptName);
    }

    /**
     * Runs the passed code in the passed scope.
     * @param script Code to evaluate
     * @param scriptName For error reporting purposes.
     * @return Result of code evaluation.
     */
    protected Object evaluate(String script, String scriptName) {
        return Context.getCurrentContext().evaluateString(programScope, script, scriptName, 1, null);
    }
    
    /**
     * Creates a snapshot of the program, which includes the status of its BThreads, 
     * and the enqueued external events.
     * 
     * <strong>
     * This method will produce unexpected results when called while the program
     * is not in BSync.
     * </strong>
     * @return A snapshot of the program.
     */
    public BProgramSyncSnapshot getSnapshot() {
        return new BProgramSyncSnapshot(bthreads, enqueuedExternalEvents);
    }

    /**
     * Registers a BThread into the program. If the program started, the BThread
     * will take part in the current bstep.
     *
     * @param bt
     */
    public void registerBThread(BThreadSyncSnapshot bt) {
        listeners.forEach(l -> l.bthreadAdded(this, bt));
        if (started) {
            recentlyRegisteredBthreads.add(bt);
        } else {
            bthreads.add(bt);
        }
    }
    
    /**
     * Creates a list of the current {@link RWBStatements} of the current
     * BThreads. Note that the order in the list is arbitrary.
     *
     * @return list of statements in arbitrary order.
     */
    public Set<BSyncStatement> currentStatements() {
        return bthreads.stream()
                .map(BThreadSyncSnapshot::getBSyncStatement)
                .collect(toSet());
    }

    /**
     * a method that sends events as input for the application
     *
     * @param e
     */
    public void enqueueExternalEvent(BEvent e) {
        recentlyEnqueuedExternalEvents.add(e);
    }

    /**
     * Sets up internal data structures for running.
     */
    protected void setup() {
        try {
            Context cx = ContextFactory.getGlobal().enterContext();
            cx.setOptimizationLevel(-1); // must use interpreter mode
            setupGlobalScope(cx);
            setupBThreadScopes();
        } finally {
            Context.exit();
        }
    }

    protected void setupAddedBThread(BThreadSyncSnapshot bt) {
        bt.setupScope(programScope);
    }

    protected void setupBThreadScopes() {
        bthreads.forEach(bt -> bt.setupScope(programScope));
    }

    private void setupGlobalScope(Context cx) {
        // load and execute globalScopeInit.js
        ImporterTopLevel importer = new ImporterTopLevel(cx);
        programScope = cx.initStandardObjects(importer);

        BProgramJsProxy proxy = new BProgramJsProxy(this);
        programScope.put("bp", programScope,
                Context.javaToJS(proxy, programScope));
        programScope.put("emptySet", programScope,
                Context.javaToJS(emptySet, programScope));
        programScope.put("all", programScope,
                Context.javaToJS(all, programScope));

        evaluateResource("globalScopeInit.js");

        setupProgramScope(programScope);
    }

    /**
     * The BProgram should set up its scope here (e.g. load the script for
     * BThreads). This method is called after {@link #setupGlobalScope()}.
     *
     * @param scope the scope to set up.
     */
    protected abstract void setupProgramScope(Scriptable scope);

    private boolean waitForExternalEvent() throws InterruptedException {
        BEvent next = recentlyEnqueuedExternalEvents.take();
        
        if (next == NO_MORE_DAEMON) {
            daemonMode = false;
            return false;
        } else {
            enqueuedExternalEvents.add(next);
            return true;
        }
    }

    /**
     * Adds all the non-empty {@link BThreadSyncSnapshot} from {@code runResults} 
     * to the next event loop round.
     * 
     * <em> All futures must be done when this method is called. This is the normal
     * case for when calling {@link ExecutorService#invokeAll}, so normally not a big requirement.</em>
     *
     * @param runResults 
     */
    private void addToNextRound( List<Future<BThreadSyncSnapshot>> runResults ) {
        if ( nextRoundBthreads == null ) {
            nextRoundBthreads = new HashSet<>(runResults.size());
        }
        nextRoundBthreads.addAll( 
            runResults.stream().map( f -> {
                try {
                    return f.get();
                } catch ( InterruptedException | ExecutionException ie ) {
                    System.out.println("**** Got an excetpion " + ie);
                    System.out.println("**** Message " + ie.getMessage());
                    ie.printStackTrace(System.out);
                    return null;
                }})
            .filter( Objects::nonNull )
            .collect(toList())
        );
    }

    /**
     * Prepares {@code this} for the next iteration of the event loop.
     */
    void finalizeRound() {
        bthreads = nextRoundBthreads;
        nextRoundBthreads = null;
    }

    /**
     * Adds a listener to the BProgram.
     * @param <R> Actual type of listener.
     * @param aListener the listener to add.
     * @return The added listener, to allow call chaining.
     */
    public <R extends BProgramListener> R addListener(R aListener) {
        listeners.add(aListener);
        return aListener;
    }

    /**
     * Removes the listener from the program. If the listener is not registered,
     * this call is ignored. In other words, this call is idempotent.
     * @param aListener the listener to remove.
     */
    public void removeListener(BProgramListener aListener) {
        listeners.remove(aListener);
    }

    /**
     * Sets whether this program is a daemon or not. When daemon, program will
     * wait for external events even when there are no selectable internal events. 
     *
     * In normal mode ({@code daemon==false}), when no events are available for 
     * selection, the program terminates.
     * 
     * @param newDaemonMode {@code true} to make the program a daemon, 
     *                      {@code false} otherwise.
     */
    public void setDaemonMode(boolean newDaemonMode) {
        if (daemonMode && !newDaemonMode) {
            daemonMode = false;
            recentlyEnqueuedExternalEvents.add(NO_MORE_DAEMON);
        } else {
            daemonMode = newDaemonMode;
        }
    }

    /**
     * Returns {@code true} iff the program is in daemon mode. When in this mode,
     * the program will not terminate when it has no event available for selection.
     * Rather, it will wait for an external event to be enqueued into its external
     * event queue.
     * 
     * @return {@code true} if this BProgram is in daemon mode,
     *         {@code false} otherwise.
     * @see #enqueueExternalEvent(il.ac.bgu.cs.bp.bpjs.events.BEvent) 
     */
    public boolean isDaemonMode() {
        return daemonMode;
    }
    
    /**
     * Returns the program's global scope.
     * @return the global scope of the program.
     */
    public Scriptable getGlobalScope() {
        return programScope;
    }

    /**
     * Returns the snapshots of all current BThreads. This method will only yield 
     * meaningful results when the program is at BSync state.
     * @return snapshots of the current BThreads.
     */
    public Set<BThreadSyncSnapshot> getBThreadSnapshots() {
        return bthreads;
    }

    /**
     * Sets the name of the program
     * @param name the new program's name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * @return the program's name
     */
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return "[BProgram " + getName() + "]";
    }
    
}
