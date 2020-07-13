package org.oddjob.jmx.handlers;

import org.oddjob.Describable;
import org.oddjob.arooa.ArooaSession;
import org.oddjob.describe.Describer;
import org.oddjob.describe.UniversalDescriber;
import org.oddjob.jmx.RemoteOperation;
import org.oddjob.jmx.client.HandlerVersion;
import org.oddjob.jmx.server.*;

import javax.management.*;
import java.util.Map;

/**
 * InterfaceHandler for the {@link Describable} interface.
 * 
 * @author Rob Gordon.
 */
public class DescribableHandlerFactory
implements ServerInterfaceHandlerFactory<Object, Describable> {

	public static final HandlerVersion VERSION = new HandlerVersion(1, 0);
	
	private static final JMXOperation<Map<String, String>> DESCRIBE = 
		new JMXOperationFactory(Describable.class
				).operationFor("describe", 
			"Describe properties.",
			MBeanOperationInfo.INFO);
	
	public Class<Object> interfaceClass() {
		return Object.class;
	}
	
	public MBeanAttributeInfo[] getMBeanAttributeInfo() {
		return new MBeanAttributeInfo[0];
	}

	public MBeanOperationInfo[] getMBeanOperationInfo() {
		return new MBeanOperationInfo[] {
			DESCRIBE.getOpInfo()
			};
	}
	
	public MBeanNotificationInfo[] getMBeanNotificationInfo() {
		return new MBeanNotificationInfo[0];
	}
	
	
	public ServerInterfaceHandler createServerHandler(Object target, ServerSideToolkit ojmb) {
		return new ServerDescribableHandler(target,
				ojmb.getServerSession().getArooaSession());
	}

	public Class<Describable> clientClass() {
		return Describable.class;
	}
	
	static class ServerDescribableHandler implements ServerInterfaceHandler {
	
		private final Object object;
		private final Describer describer;
		
		ServerDescribableHandler(Object object, ArooaSession session) {
			this.object = object;
			this.describer = new UniversalDescriber(session);
		}
		
		public Object invoke(RemoteOperation<?> operation, Object[] params) 
		throws MBeanException, ReflectionException {

			if (DESCRIBE.equals(operation)) {
				return describer.describe(object);
			}
			else {
				throw new ReflectionException(
						new IllegalStateException("invoked for an unknown method."), 
								operation.getActionName());				
			}
		}
		
		public void destroy() {
		}
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == null) {
			return false;
		}
		return obj.getClass() == this.getClass();
	}
	
	@Override
	public int hashCode() {
		return getClass().hashCode();
	}

}