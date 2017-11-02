package il.ac.bgu.cs.bp.bpjs.search;

import il.ac.bgu.cs.bp.bpjs.search.bprogramio.BProgramSyncSnapshotCloner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;

import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.BProgram;
import il.ac.bgu.cs.bp.bpjs.bprogram.runtimeengine.BProgramSyncSnapshot;
import il.ac.bgu.cs.bp.bpjs.events.BEvent;
import java.util.Set;

/**
 * A single node in a program's execution tree. Contains the program's state,
 * and the last event to happen when getting to this state.
 * 
 * @author Gera
 * @author Reut
 * @author michael
 */
public class Node {
    
    /**
     * Get the initial nod ofr a run of the passed {@code BPorgram}.
     * @param bp The {@link BProgram} being verified.
     * @return Initial node for the BProgram run
     * @throws Exception 
     */
	public static Node getInitialNode(BProgram bp) throws Exception {
		BProgramSyncSnapshot seed = bp.setup().start();

		return new Node(bp, seed, null);
	}
    
	private final BProgramSyncSnapshot systemState;
	private final BProgram bp;
	private final Set<BEvent> selectableEvents;
	private final BEvent lastEvent;
	private final Iterator<BEvent> iterator;

	protected Node(BProgram bp, BProgramSyncSnapshot systemState, BEvent e) {
		this.bp = bp;
		this.systemState = systemState;
		this.lastEvent = e;
        
        if ( bp != null ) {
            selectableEvents = bp.getEventSelectionStrategy().selectableEvents(systemState.getStatements(),
				systemState.getExternalEvents());
            ArrayList<BEvent> eventOrdered = new ArrayList<>(selectableEvents);
            Collections.shuffle(eventOrdered);
            iterator = eventOrdered.iterator();
        } else {
            selectableEvents = Collections.<BEvent>emptySet();
            iterator = selectableEvents.iterator();
        }
	
	}

	private String stateString() {

		StringBuilder str = new StringBuilder();
		systemState.getBThreadSnapshots().forEach(
				s -> str.append("\t").append(s.toString()).append(" {").append(s.getBSyncStatement()).append("} \n"));

		return str.toString();
	}

	@Override
	public String toString() {
		return ((lastEvent != null) ? "\n\tevent: " + lastEvent + "\n" : "") + stateString();
	}


	/**
	 * Get a Node object for each possible state of the system after triggering the
	 * given event.
	 * 
	 * @param e
	 * @return State of the BProgram after event {@code e} was selected while the
	 *         program was at {@code this} node's state.
	 * @throws InterruptedException
	 */
	public Node getNextNode(BEvent e) throws Exception {
		return new Node(bp, BProgramSyncSnapshotCloner.clone(systemState).triggerEvent(e), e);
	}

	/**
	 * Check if this state is good or bad
	 * 
	 * @return True if the state is good.
	 */
	public boolean check() {
		return !selectableEvents.isEmpty();
	}

	/**
	 * Get the events that can be triggered at the state.
	 * 
	 * @return An iterator for the set of requested and not blocked events.
	 */
	public Iterator<BEvent> getEventIterator() {
		return iterator;
	}

	public BEvent getLastEvent() {
		return lastEvent;
	}

	public BProgramSyncSnapshot getSystemState() {
		return systemState;
	}

    public Set<BEvent> getSelectableEvents() {
        return selectableEvents;
    }
    
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Objects.hash(systemState);
		result = prime * result + Objects.hash(lastEvent);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof Node))
			return false;

		Node other = (Node) obj;
		if (!Objects.equals(lastEvent, other.getLastEvent()))
			return false;

		return Objects.equals(systemState, other.getSystemState());
	}

}
