package org.oddjob.jobs.structural;

import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.oddjob.FailedToStopException;
import org.oddjob.Loadable;
import org.oddjob.Stateful;
import org.oddjob.Stoppable;
import org.oddjob.arooa.ArooaConfiguration;
import org.oddjob.arooa.ArooaDescriptor;
import org.oddjob.arooa.ArooaParseException;
import org.oddjob.arooa.ArooaSession;
import org.oddjob.arooa.ArooaTools;
import org.oddjob.arooa.ComponentTrinity;
import org.oddjob.arooa.ConfigurationHandle;
import org.oddjob.arooa.convert.ArooaConversionException;
import org.oddjob.arooa.convert.ArooaConverter;
import org.oddjob.arooa.deploy.annotations.ArooaAttribute;
import org.oddjob.arooa.deploy.annotations.ArooaComponent;
import org.oddjob.arooa.design.DesignFactory;
import org.oddjob.arooa.life.ComponentPersister;
import org.oddjob.arooa.life.ComponentProxyResolver;
import org.oddjob.arooa.parsing.ArooaElement;
import org.oddjob.arooa.parsing.ConfigConfigurationSession;
import org.oddjob.arooa.parsing.ConfigurationOwner;
import org.oddjob.arooa.parsing.ConfigurationOwnerSupport;
import org.oddjob.arooa.parsing.ConfigurationSession;
import org.oddjob.arooa.parsing.DragPoint;
import org.oddjob.arooa.parsing.OwnerStateListener;
import org.oddjob.arooa.parsing.SessionStateListener;
import org.oddjob.arooa.reflect.PropertyAccessor;
import org.oddjob.arooa.registry.BeanDirectory;
import org.oddjob.arooa.registry.BeanRegistry;
import org.oddjob.arooa.registry.ComponentPool;
import org.oddjob.arooa.registry.SimpleBeanRegistry;
import org.oddjob.arooa.registry.SimpleComponentPool;
import org.oddjob.arooa.runtime.PropertyManager;
import org.oddjob.arooa.standard.StandardArooaParser;
import org.oddjob.arooa.standard.StandardPropertyManager;
import org.oddjob.arooa.utils.RootConfigurationFileCreator;
import org.oddjob.arooa.xml.XMLConfiguration;
import org.oddjob.designer.components.ForEachRootDC;
import org.oddjob.framework.StructuralJob;
import org.oddjob.io.ExistsJob;
import org.oddjob.logging.OddjobNDC;
import org.oddjob.state.IsHardResetable;
import org.oddjob.state.IsNotExecuting;
import org.oddjob.state.ParentState;
import org.oddjob.state.SequentialHelper;
import org.oddjob.state.State;
import org.oddjob.state.StateEvent;
import org.oddjob.state.StateListener;
import org.oddjob.state.StateOperator;
import org.oddjob.state.WorstStateOp;


/**
 * @oddjob.description A job which executes its child jobs for
 * each of the provided values. The child job can access the current
 * value using the pseudo property 'current' to gain access to the
 * current value. The pseudo property 'index' provides a 0 based number for
 * the instance. 
 * <p>
 * The return state of this job depends on the return state
 * of the children (like {@link SequentialJob}). Hard resetting this job
 * will cause the children to be destroyed and recreated on the next run
 * (with possibly new values). Soft resetting this job will reset the 
 * children but when re-run will not reconfigure the values.
 * <p>
 * As yet There is no persistence for child jobs.
 * <p>
 * It is not possible to reference the internal jobs via their id from 
 * outside the foreach job, but within
 * the foreach internal configuration they can reference each other and 
 * themselves via their ids.
 * <p>
 * 
 * @oddjob.example
 * 
 * For each of 3 values.
 * 
 * {@oddjob.xml.resource org/oddjob/jobs/structural/ForEachWithIdsExample.xml}
 * 
 * The internal configuration is:
 * 
 * {@oddjob.xml.resource org/oddjob/jobs/structural/ForEachEchoColour.xml}
 * 
 * Unlike other jobs, a job in a for each has it's name configured when it is 
 * loaded, before it is run. The job references its self using its id.
 * 
 * @oddjob.example
 * 
 * For each of 3 files.
 * 
 * {@oddjob.xml.resource org/oddjob/jobs/structural/ForEachFilesExample.xml}
 * 
 * @oddjob.example
 * 
 * Also {@link ExistsJob} has a similar example.
 */

public class ForEachJob extends StructuralJob<Runnable>
implements Stoppable, Loadable, ConfigurationOwner {
    private static final long serialVersionUID = 200903212011060700L;
	
    /** Root element for configuration. */
    public static final ArooaElement FOREACH_ELEMENT = 
    	new ArooaElement("foreach");
    
    /**
     * @oddjob.property values
     * @oddjob.description Any value.
     * @oddjob.required No.
     */
	private transient Iterable<? extends Object> values;
    		
	/** The current iterator. */
	private transient Iterator<? extends Object> iterator;
	
    /**
     * @oddjob.property 
     * @oddjob.description The number of values to pre-load configurations for. 
     * This property can be used with large sets of values to ensure that only a 
     * certain number are pre-loaded before execution starts.
     * @oddjob.required No. Defaults to all configurations being loaded first.
     */
	private int preLoad;
	
    /**
     * @oddjob.property 
     * @oddjob.description The number of completed jobs to keep. Oddjob configurations
     * can be quite memory intensive, mainly due to logging, purging complete jobs
     * will stop too much memory being taken. 
 	 *
     * @oddjob.required No. Defaults to no complete jobs being purged.
     */
	private int purgeAfter;
	
	/**
	 * @oddjob.property
	 * @oddjob.description The configuration that will be parsed
	 * for each value.
	 * @oddjob.required Yes.
	 */
    private transient ArooaConfiguration configuration;
    
	/** The configuration file. */
	private File file;
		    
	/** Support for configuration modification. */
	private transient ConfigurationOwnerSupport configurationOwnerSupport;
	
	/**
	 * @oddjob.property
	 * @oddjob.description The current value
	 * @oddjob.required R/O.
	 */
	private transient Object current;

	/**
	 * @oddjob.property
	 * @oddjob.description The current index in the
	 * values.
	 * @oddjob.required R/O.
	 */
	private transient int index;
		
    /** Track configuration so they can be destroyed. */
    private transient Map<Object, ConfigurationHandle> configurationHandles;
    
    private transient LinkedList<Runnable> ready;
    
    private transient LinkedList<Stateful> complete;
    
    public ForEachJob() {
    	completeConstruction();
	}
    
	private void completeConstruction() {
		configurationOwnerSupport =
			new ConfigurationOwnerSupport(this);		
	}

    
    
    
	/**
	 * The current value.
	 * 
	 * @return The current value.
	 */
	public Object getCurrent() {
	    return current;
	}
	
	/**
	 * Add a type. This will be called during parsing by the
	 * handler to add a type for each element.
 	 * 
	 * @param type The type.
	 */
	public void setValues(Iterable<? extends Object> values) {
		this.values = values;
	}

	@Override
	protected StateOperator getStateOp() {
		return new WorstStateOp();
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.arooa.parsing.ConfigurationOwner#provideConfigurationSession()
	 */
	@Override
	public ConfigurationSession provideConfigurationSession() {
		
		return configurationOwnerSupport.provideConfigurationSession();
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.arooa.parsing.ConfigurationOwner#addOwnerStateListener(org.oddjob.arooa.parsing.OwnerStateListener)
	 */
	@Override
	public void addOwnerStateListener(OwnerStateListener listener) {
		configurationOwnerSupport.addOwnerStateListener(listener);
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.arooa.parsing.ConfigurationOwner#removeOwnerStateListener(org.oddjob.arooa.parsing.OwnerStateListener)
	 */
	@Override
	public void removeOwnerStateListener(OwnerStateListener listener) {
		configurationOwnerSupport.removeOwnerStateListener(listener);
	}
	
	@Override
	public DesignFactory rootDesignFactory() {
		return new ForEachRootDC();
	}
	
	@Override
	public ArooaElement rootElement() {
		return FOREACH_ELEMENT;
	}
	
	/**
	 * Load a configuration for a single value.
	 * 
	 * @param value
	 * @throws ArooaParseException
	 */
	protected void loadConfigFor(Object value) throws ArooaParseException {
		
		logger().debug("creating child for [" + value + "]");
		
		ArooaSession existingSession = getArooaSession();
		
		PsudoRegistry psudoRegistry = new PsudoRegistry(
				existingSession.getBeanRegistry(),
				existingSession.getTools().getPropertyAccessor(),
				existingSession.getTools().getArooaConverter());

		RegistryOverrideSession session = new RegistryOverrideSession(
				existingSession, psudoRegistry);
		
		LocalBean seed = new LocalBean(index++, value);
		
		StandardArooaParser parser = new StandardArooaParser(seed,
				session);
		parser.setExpectedDocumentElement(FOREACH_ELEMENT);
		
		ConfigurationHandle handle = parser.parse(configuration);
		
		Runnable root = (Runnable) seed.job;

		if (root == null) {
			logger().info("No child job created.");
			return;
		}

		// Configure the root so we can see the name if it uses the
		// current value.
		handle.getDocumentContext().getSession(
				).getComponentPool().configure(root);
		
		configurationHandles.put(root, handle);			
		
	    childHelper.addChild(root);
	    
	    if (root instanceof Stateful) {
	    	((Stateful) root).addStateListener(new StateListener() {
				
				@Override
				public void jobStateChange(StateEvent event) {
					Stateful source = event.getSource();					
					State state = event.getState();
					
					if (state.isReady()) {
					    ready.add((Runnable) source);
					}
					else if (state.isComplete()) {
						complete.add(source);
					}
				}
			});
	    }
	    else {
	    	// Support for none stateful jobs. Should we support this?
	    	ready.add(root);
	    }	    
	}

	private void remove(Object child) {
		
		childHelper.removeChild(child);

		ConfigurationHandle handle = configurationHandles.get(child);
		handle.getDocumentContext().getRuntime().destroy();
	}
	
	protected void preLoad() throws ArooaParseException {
	    
		if (values == null) {
			throw new IllegalStateException("No values supplied.");
		}
		if (configuration == null) {
			throw new IllegalStateException("No configuration.");
		}
		if (getArooaSession() == null) {
			throw new NullPointerException("No ArooaSession.");
		}
		
		// already loaded?
		if (configurationHandles != null) {
			return;
		}
		
        configurationOwnerSupport.setConfigurationSession(
				new ForeachConfigurationSession(
						new ConfigConfigurationSession(
								getArooaSession(), configuration)));
        
	    logger().debug("Creating children from configuration.");
	    
		configurationHandles = new HashMap<Object, ConfigurationHandle>();
		ready = new LinkedList<Runnable>();
		complete = new LinkedList<Stateful>();
		
		iterator = values.iterator();
		
		loadNext();
	}
	
	private boolean loadNext() throws ArooaParseException {
		
		// load child jobs for each value
		while (preLoad < 1 || ready.size() < preLoad) {
			if (iterator.hasNext()) {
				loadConfigFor(iterator.next());
			}
			else {
				return false;
			}
		}		
		return true;
	}
	
	public void load() {
		OddjobNDC.push(loggerName());
		try {
			stateHandler.waitToWhen(new IsNotExecuting(), new Runnable() {
				public void run() {
				    try {
						if (configurationHandles != null) {
							return;
						}
				    	configure();
				    	
				    	preLoad();
				    }
				    catch (Exception e) {
				    	logger().error("[" + ForEachJob.this + "] Exception executing job.", e);
				    	getStateChanger().setStateException(e);
				    }
				}
			});
		}
		finally {
			OddjobNDC.pop();
		}
	};
	
	@Override
	public void unload() {
		reset();
	}
	
	public boolean isLoadable() {
		return configurationHandles == null;
	}
	
	/*
	 *  (non-Javadoc)
	 * @see org.oddjob.jobs.AbstractJob#execute()
	 */
	protected void execute() throws Exception {

		preLoad();
		
		while (!stop) {
			
			loadNext();
			
			while (purgeAfter > 0 && complete.size() > purgeAfter) {
				
				remove(complete.removeFirst());
			}
			
			if (ready.size() > 0) {
				
				Runnable job = ready.removeFirst();	
				
				job.run();
				
				// Test we can still execute children.
				if (!new SequentialHelper().canContinueAfter(job)) {
					logger().info("Job [" + job + "] failed. Can't continue.");
					break;
				}				
			}
			else {
				break;
			}
		}		
		
		stop = false;
	}
	    
    /**
     * @return Returns the index.
     */
    public int getIndex() {
        return index;
    }
        
    /**
     * This provides a bean for current properties.
     */
    public static class LocalBean {
    	
    	private final int index;
    	private final Object current;

    	private Object job;
    	
    	LocalBean (int index, Object value) {
    		this.index = index;
    		this.current = value;
    	}
    	public Object getCurrent() {
    		return current;
    	}
    	public int getIndex() {
    		return index;
    	}
    	
    	@ArooaComponent
	    public void setJob(Object child) {
	    	job = child;
	    }
    }

    class RegistryOverrideSession implements ArooaSession {

    	private final ArooaSession existingSession;
    	
    	private final BeanRegistry beanDirectory;
    	
    	private final ComponentPool componentPool;
    	
    	private final PropertyManager propertyManager;
    	
    	public RegistryOverrideSession(
    			ArooaSession exsitingSession,
    			BeanRegistry registry) {
    		this.existingSession = exsitingSession;
    		this.beanDirectory = registry;
    		this.componentPool = new PsudoComponentPool(
    				exsitingSession.getComponentPool());
    		this.propertyManager = new StandardPropertyManager(
    				existingSession.getPropertyManager());
		}
    	
    	@Override
    	public ArooaDescriptor getArooaDescriptor() {
    		return existingSession.getArooaDescriptor();
    	}
    	
    	@Override
    	public ComponentPool getComponentPool() {
    		return componentPool;
    	}

    	@Override
    	public BeanRegistry getBeanRegistry() {
    		return beanDirectory;
    	}
    	
    	@Override
    	public PropertyManager getPropertyManager() {
    		return propertyManager;
    	}
    	
    	@Override
    	public ComponentProxyResolver getComponentProxyResolver() {
    		return existingSession.getComponentProxyResolver();
    	}
    	
    	@Override
    	public ComponentPersister getComponentPersister() {
    		return null;
    	}
    	
    	@Override
    	public ArooaTools getTools() {
    		return existingSession.getTools();
    	}
    }
    
    class PsudoRegistry extends SimpleBeanRegistry {
    	private final BeanDirectory existingDirectory;    	
    	    	
    	PsudoRegistry(BeanDirectory existingDirectory,
    			PropertyAccessor propertyAccessor,
    			ArooaConverter converter) {
    		super(propertyAccessor, converter);
    		this.existingDirectory = existingDirectory;
    	}
    			
		/**
		 * First try our local registry then the parent.
		 * 
		 */
    	public Object lookup(String path) {
			Object component = super.lookup(path);
			if (component == null) {
				return existingDirectory.lookup(path);
			}
			return component;
    	}
    	
    	@Override
    	public <T> T lookup(String path, Class<T> required)
    			throws ArooaConversionException {
			T component = super.lookup(path, required);
			if (component == null) {
				return existingDirectory.lookup(path, required);
			}
			return component;
    	}
    	
    	
    	
		/**
		 * This stops serialisation working for child components. Need to revisit
		 * this if it becomes a requirement!
		 */
		public String getIdFor(Object component) {
			return null;
		}
		
		@Override
		public synchronized <T> Iterable<T> getAllByType(Class<T> type) {
			List<T> results = new ArrayList<T>();
			for (T t : super.getAllByType(type)) {
				results.add(t);
			}
			for (T t : existingDirectory.getAllByType(type)) {
				results.add(t);
			}
			return results;
		}
    }
    
    class PsudoComponentPool extends SimpleComponentPool {
    	
    	private final ComponentPool existingPool;
    	
    	public PsudoComponentPool(ComponentPool existingPool) {
    		this.existingPool = existingPool;
		}
    	
    	@Override
    	public Iterable<ComponentTrinity> allTrinities() {
			List<ComponentTrinity> results = new ArrayList<ComponentTrinity>();
			for (ComponentTrinity t : super.allTrinities()) {
				results.add(t);
			}
			for (ComponentTrinity t : existingPool.allTrinities()) {
				results.add(t);
			}
			return results;
    	}
    }
    
	private void reset() {
		
	    if (configurationHandles == null) {
			return;
		}
		
		try {
			childHelper.stopChildren();
		} catch (FailedToStopException e) {
			logger().warn(e);
		}
		
		Object[] children = childHelper.getChildren();
		
		for (Object child : children) {
			remove(child);
		}
		
	    this.configurationHandles = null;
	    this.ready = null;
	    this.complete = null;	    
		this.index = 0;
		this.stop = false;
		
		configurationOwnerSupport.setConfigurationSession(null);		
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
				
		reset();
	}
	
	/**
	 * Perform a hard reset on the job.
	 */
	public boolean hardReset() {
		return stateHandler.waitToWhen(new IsHardResetable(), new Runnable() {
			public void run() {
				childStateReflector.stop();
				
				reset();
				
				getStateChanger().setState(ParentState.READY);
				logger().info("[" + ForEachJob.this + "] Hard Reset." );
			}
		});
	}

	/**
	 * @oddjob.property file
	 * @oddjob.description The name of the configuration file.
	 * to use for configuration.
	 * @oddjob.required No.
	 * 
	 * @return The file name.
	 */
	@ArooaAttribute
	public void setFile(File file) {

		this.file = file;
		if (file == null) {
			this.file = null;
			configuration = null;
		}
		else {
			new RootConfigurationFileCreator(
					FOREACH_ELEMENT).createIfNone(file);
			this.file = file;
			configuration = new XMLConfiguration(file);
		} 
	}

	public File getFile() {
		if (file == null) {
			return null;
		}
		return file.getAbsoluteFile();
	}
	
	public ArooaConfiguration getConfiguration() {
		return configuration;
	}

	public void setConfiguration(ArooaConfiguration configuration) {
		this.configuration = configuration;
	}

	public int getPreLoad() {
		return preLoad;
	}

	public void setPreLoad(int preLoad) {
		this.preLoad = preLoad;
	}

	public int getPurgeAfter() {
		return purgeAfter;
	}

	public void setPurgeAfter(int purgeAfter) {
		this.purgeAfter = purgeAfter;
	}
	
	/*
	 * Custom serialization.
	 */
	private void writeObject(ObjectOutputStream s) 
	throws IOException {
		s.defaultWriteObject();
	}
	
	/*
	 * Custom serialization.
	 */
	private void readObject(ObjectInputStream s) 
	throws IOException, ClassNotFoundException {
		s.defaultReadObject();
		completeConstruction();
	}
	
	/**
	 * Only the root foreach should result in a drag point..
	 */
	class ForeachConfigurationSession implements ConfigurationSession {

		private final ConfigurationSession delegate;
		
		public ForeachConfigurationSession(ConfigurationSession delegate) {
			this.delegate = delegate;
		}
		
		public DragPoint dragPointFor(Object component) {
			// required for the Design Inside action.
			if (component == ForEachJob.this) {
				return delegate.dragPointFor(component);
			}
			else {
				return null;
			}
		}
		
		public ArooaDescriptor getArooaDescriptor() {
			return delegate.getArooaDescriptor();
		}
		
		public void save() throws ArooaParseException {
			delegate.save();
		}
		
		public boolean isModified() {
			return delegate.isModified();
		}
		
		public void addSessionStateListener(SessionStateListener listener) {
			delegate.addSessionStateListener(listener);
		}
		
		public void removeSessionStateListener(SessionStateListener listener) {
			delegate.removeSessionStateListener(listener);
		}
	}
}

