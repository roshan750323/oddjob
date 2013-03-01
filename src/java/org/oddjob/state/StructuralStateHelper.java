package org.oddjob.state;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.log4j.Logger;
import org.oddjob.Stateful;
import org.oddjob.Structural;
import org.oddjob.structural.StructuralEvent;
import org.oddjob.structural.StructuralListener;

/**
 * Track, and aggregate the states of child jobs. Aggregation is
 * achieved using the given {@link StateOperator}.
 * 
 * @author rob
 *
 */
public class StructuralStateHelper implements Stateful {
	private static final Logger logger = Logger.getLogger(
			StructuralStateHelper.class);

	/** The structural we're helping. */
	private final Structural structural;
	
	/** Used to capture state into. */
	private final ParentStateHandler stateHandler = 
		new ParentStateHandler(this);
	
	/** The states of the children. *
	 * References are required because the position of the children
	 * can move. The state listener uses a reference instead
	 * of an index to insert state.
	 */
	private final List<AtomicReference<State>> states = 
		new ArrayList<AtomicReference<State>>();
	
	/** The listeners listening to the children. */
	private final List<StateListener> listeners = 
		new ArrayList<StateListener>();

	/** The {@link StateOperator}. */
	private StateOperator stateOperator;

	/**
	 * Listens to a single child's state.
	 */
	class ChildStateListener implements StateListener {

		private final AtomicReference<State> holder;
		
		public ChildStateListener(AtomicReference<State> holder) {
			this.holder = holder;
		}
		
		@Override
		public synchronized void jobStateChange(StateEvent event) {
			
			State previous = holder.getAndSet(event.getState());
			
			// Don't check when listener initially added as this happens
			// in when child added.
			if (previous != null) {
				checkStates();
			}
		};
		
		@Override
		public String toString() {
			return getClass().getName() + " for [" + structural + "]";
		}
	};
	
	/**
	 * Constructor.
	 * 
	 * @param structural
	 * @param operator
	 */
	public StructuralStateHelper(Structural structural, StateOperator operator) {
		this.structural = structural;
		this.stateOperator = operator;
		
		// Add a listener that tracks child changes.
		structural.addStructuralListener(
				new StructuralListener() {
					
			@Override
			public void childAdded(final StructuralEvent event) {
				stateHandler.waitToWhen(new IsAnyState(), new Runnable() {
					@Override
					public void run() {
						int index = event.getIndex();
						Object child = event.getChild();
		
						AtomicReference<State> stateHolder = 
								new AtomicReference<State>();
		
						ChildStateListener listener = 
								new ChildStateListener(stateHolder);
		
						if (child instanceof Stateful) {
							((Stateful) child).addStateListener(listener);
						}
						else {
							stateHolder.set(ParentState.COMPLETE);
						}
						
						listeners.add(index, listener);
						states.add(index, stateHolder);
						
						checkStates();
					}
				});
			}
				
			@Override
			public void childRemoved(final StructuralEvent event) {
				stateHandler.waitToWhen(new IsAnyState(), new Runnable() {
					@Override
					public void run() {
						int index = event.getIndex();
						Object child = event.getChild();


						StateListener listener = listeners.remove(index);

						if (child instanceof Stateful) {
							((Stateful) child).removeStateListener(listener);
						}

						states.remove(index);

						checkStates();
					}
				});
			}				
		});
	}
	
	public void addStateListener(StateListener listener) {
		stateHandler.addStateListener(listener);
	}
	
	public void removeStateListener(StateListener listener) {
		stateHandler.removeStateListener(listener);		
	}
			
	private void checkStates() {
		
		stateHandler.waitToWhen(new IsAnyState(), new Runnable() {
			public void run() {
				State[] stateArgs = new State[states.size()];
				int i = 0;
				for (AtomicReference<State> holder : states) {
					stateArgs[i++] = holder.get(); 
				}
				
				ParentState state = stateOperator.evaluate(stateArgs);
						
				// don't fire a new state if it is the same as the last.
				if (state == stateHandler.getState()) {
					return;
				}
				
				stateHandler.setState(state);
				stateHandler.fireEvent();
			}
		});
	}

	@Override
	public StateEvent lastStateEvent() {
		return stateHandler.lastStateEvent();
	}
	
	public State[] getChildStates() {
		
		return stateHandler.callLocked(new Callable<State[]>() {
			@Override
			public State[] call() throws Exception {
				State[] array = new State[states.size()];
				int i = 0;
				for (AtomicReference<State> holder : states) {
					array[i++] = holder.get();
				}
				return array;
			}
		});
	}

	public StateOperator getStateOperator() {
		return stateOperator;
	}

	public void setStateOperator(StateOperator stateOperator) {
		
		if (stateOperator == null || 
				stateOperator.equals(this.stateOperator)) {
			return;
		}

		logger.info("Changing State Operator from " + this.stateOperator +
				" to " + stateOperator);
		
		this.stateOperator = stateOperator;
		checkStates();
	}
}
