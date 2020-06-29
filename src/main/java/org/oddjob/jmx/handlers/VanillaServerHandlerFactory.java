package org.oddjob.jmx.handlers;

import org.oddjob.jmx.client.ClientHandlerResolver;
import org.oddjob.jmx.client.VanillaHandlerResolver;
import org.oddjob.jmx.server.*;

import javax.management.MBeanAttributeInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanOperationInfo;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * Factory that provides handlers for an interface where 
 * the invocation of methods on that interface are just
 * straight to the JMX MBean.
 * <p>
 * Use this when there is no call backs, and no final
 * properties of the target where performance might
 * benefit from caching.
 * 
 * @author rob
 *
 */
public class VanillaServerHandlerFactory<T> 
implements ServerInterfaceHandlerFactory<T, T> {

	/** The interface class. */
	private final Class<T> cl;
	
	/** The operation info for the management interface. */
	private final MBeanOperationInfo[] opInfo;
	
	/**
	 * Constructor.
	 * 
	 * @param cl The interface for which we wish to provide
	 * handlers.
	 */
	public VanillaServerHandlerFactory(Class<T> cl) {
		Objects.requireNonNull(cl);
		
		this.cl = cl;	
		Method[] ms = cl.getMethods();
		opInfo = new MBeanOperationInfo[ms.length];
		for (int i = 0; i < opInfo.length; ++i) {
			Method m = ms[i];
						
			JMXOperation<?> op = 
				new JMXOperationFactory(cl).operationFor(m, MBeanOperationInfo.UNKNOWN);
			
			opInfo[i] = op.getOpInfo();
		}
	}
		
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.jmx.server.ServerInterfaceHandlerFactory#interfaceClass()
	 */
	public Class<T> interfaceClass() {
		return cl;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.jmx.server.ServerInterfaceHandlerFactory#getMBeanAttributeInfo()
	 */
	public MBeanAttributeInfo[] getMBeanAttributeInfo() {
		return new MBeanAttributeInfo[0];
	}

	/*
	 * (non-Javadoc)
	 * @see org.oddjob.jmx.server.ServerInterfaceHandlerFactory#getMBeanOperationInfo()
	 */
	public MBeanOperationInfo[] getMBeanOperationInfo() {
		return opInfo;
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.jmx.server.ServerInterfaceHandlerFactory#getMBeanNotificationInfo()
	 */
	public MBeanNotificationInfo[] getMBeanNotificationInfo() {
		return new MBeanNotificationInfo[0];
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.oddjob.jmx.server.ServerInterfaceHandlerFactory#createServerHandler(java.lang.Object, org.oddjob.jmx.server.ServerSideToolkit)
	 */
	public ServerInterfaceHandler createServerHandler(T target, ServerSideToolkit ojmb) {
		return new ServerAllOperationsHandler<>(cl, target);
	}

	public ClientHandlerResolver<T> clientHandlerFactory() {
		return new VanillaHandlerResolver<>(cl.getName());
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		
		if (obj.getClass() != this.getClass()) {
			return false;
		}
		
		return ((VanillaServerHandlerFactory<?>) obj).cl == this.cl;
	}
	
	@Override
	public int hashCode() {
		return getClass().hashCode() * 31 + cl.hashCode();
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + ", for class=" + cl;
	}
}
