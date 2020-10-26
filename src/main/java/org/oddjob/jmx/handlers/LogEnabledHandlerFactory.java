package org.oddjob.jmx.handlers;

import org.oddjob.jmx.RemoteOperation;
import org.oddjob.jmx.client.ClientInterfaceHandlerFactory;
import org.oddjob.jmx.client.ClientSideToolkit;
import org.oddjob.jmx.client.HandlerVersion;
import org.oddjob.jmx.server.*;
import org.oddjob.logging.LogEnabled;
import org.oddjob.logging.LogHelper;

import javax.management.*;
import java.lang.reflect.UndeclaredThrowableException;

/**
 */
public class LogEnabledHandlerFactory 
implements ServerInterfaceHandlerFactory<Object, LogEnabled> {
	
	public static final HandlerVersion VERSION = new HandlerVersion(2, 0);
	
	private static final JMXOperation<String> GET_LOGGER = 
		new JMXOperationFactory(LogEnabled.class
				).operationFor("loggerName", MBeanOperationInfo.INFO);

	@Override
	public Class<Object> serverClass() {
		return Object.class;
	}

	@Override
	public Class<LogEnabled> clientClass() {
		return LogEnabled.class;
	}

	@Override
	public HandlerVersion getHandlerVersion() {
		return VERSION;
	}

	@Override
	public MBeanAttributeInfo[] getMBeanAttributeInfo() {
		return new MBeanAttributeInfo[0];
	}

	@Override
	public MBeanOperationInfo[] getMBeanOperationInfo() {
		return new MBeanOperationInfo[] {
			GET_LOGGER.getOpInfo() };
	}

	@Override
	public MBeanNotificationInfo[] getMBeanNotificationInfo() {
		return new MBeanNotificationInfo[0];
	}

	@Override
	public ServerInterfaceHandler createServerHandler(Object target, ServerSideToolkit ojmb) {
		return new LogEnabledServerHandler(target, ojmb);
	}

	public static class ClientFactory
	implements ClientInterfaceHandlerFactory<LogEnabled> {

		@Override
		public LogEnabled createClientHandler(LogEnabled ignored, ClientSideToolkit toolkit) {
			return new ClientLogEnabledHandler(toolkit);
		}

		@Override
		public HandlerVersion getVersion() {
			return VERSION;
		}

		@Override
		public Class<LogEnabled> interfaceClass() {
			return LogEnabled.class;
		}
	}
	
	static class ClientLogEnabledHandler implements LogEnabled {
		
		/** Remember remote logger. */
		private final String remoteLoggerName;
		
		ClientLogEnabledHandler(ClientSideToolkit toolkit) {
			try {
				remoteLoggerName = toolkit.invoke(GET_LOGGER);
			} catch (Throwable t) {
				throw new UndeclaredThrowableException(t);
			}
		}

		@Override
		public String loggerName() {
			return remoteLoggerName;
		}
	}
	

	
	static class LogEnabledServerHandler implements ServerInterfaceHandler {
	
		private final String loggerName;
		
		LogEnabledServerHandler(Object object, ServerSideToolkit ojmb) {
			this.loggerName = LogHelper.getLogger(object);
		}

		@Override
		public Object invoke(RemoteOperation<?> operation, Object[] params)
		throws MBeanException, ReflectionException {

			if (GET_LOGGER.equals(operation)) {
				return loggerName;
			}
			else {
				throw new ReflectionException(
						new IllegalStateException("invoked for an unknown method."), 
								operation.toString());				
			}
		}

		@Override
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