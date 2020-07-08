/*
 * (c) Rob Gordon 2005
 */
package org.oddjob.jmx.handlers;

import org.oddjob.Structural;
import org.oddjob.jmx.RemoteOperation;
import org.oddjob.jmx.client.ClientInterfaceHandlerFactory;
import org.oddjob.jmx.client.ClientSideToolkit;
import org.oddjob.jmx.client.HandlerVersion;
import org.oddjob.jmx.client.Synchronizer;
import org.oddjob.jmx.server.*;
import org.oddjob.remote.Notification;
import org.oddjob.remote.NotificationType;
import org.oddjob.structural.ChildHelper;
import org.oddjob.structural.ChildMatch;
import org.oddjob.structural.StructuralEvent;
import org.oddjob.structural.StructuralListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.*;
import java.io.Serializable;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class StructuralHandlerFactory 
implements ServerInterfaceHandlerFactory<Structural, Structural> {
	
	private static final Logger logger = LoggerFactory.getLogger(StructuralHandlerFactory.class);
	
	public static final HandlerVersion VERSION = new HandlerVersion(1, 0);
	
	public static final NotificationType<ChildData> STRUCTURAL_NOTIF_TYPE =
			NotificationType.ofName("org.oddjob.structural")
			.andDataType(ChildData.class);

	@SuppressWarnings({"unchecked", "rawtypes"})
	static final JMXOperationPlus<Notification<ChildData>>  SYNCHRONIZE =
			new JMXOperationPlus(
					"structuralSynchronize",
					"Synchronize Notifications.",
					Notification.class,
					MBeanOperationInfo.INFO);
	
	public Class<Structural> interfaceClass() {
		return Structural.class;
	}
	
	public MBeanAttributeInfo[] getMBeanAttributeInfo() {
		return new MBeanAttributeInfo[0];
	}

	public MBeanOperationInfo[] getMBeanOperationInfo() {
		return new MBeanOperationInfo[] {
				SYNCHRONIZE.getOpInfo()
		};
	}
	
	public MBeanNotificationInfo[] getMBeanNotificationInfo() {

		return new MBeanNotificationInfo[] {
				new MBeanNotificationInfo(new String[] {
						STRUCTURAL_NOTIF_TYPE.getName() },
						Notification.class.getName(), "Structural notification.")};
	}

	public ServerInterfaceHandler createServerHandler(
			Structural structural, 
			ServerSideToolkit ojmb) {

		return new ServerStructuralHelper(structural, ojmb);
	}
	
	public Class<Structural> clientClass() {
		return Structural.class;
	}
	
	public static class ClientFactory
	implements ClientInterfaceHandlerFactory<Structural> {
		
		public Class<Structural> interfaceClass() {
			return Structural.class;
		}
		
		public HandlerVersion getVersion() {
			return VERSION;
		}
		
		public Structural createClientHandler(Structural proxy, ClientSideToolkit toolkit) {
			return new ClientStructuralHandler(proxy, toolkit);
		}		
	}
	
	static class ClientStructuralHandler 
	implements Structural {

		private final Structural proxy;
		
		/** Helper class to handle structure change */
		private ChildHelper<Object> structuralHelper;

		private final ClientSideToolkit toolkit;

		private Synchronizer<ChildData> synchronizer;
		
		private List<Long> childNames;
		
		ClientStructuralHandler(Structural proxy, ClientSideToolkit toolkit) {
			this.proxy = proxy;
			this.toolkit = toolkit;
		}
		
		/*
		 * Add a structural listener. From the Structural interface.
		 */
		public void addStructuralListener(StructuralListener listener) {
			synchronized (this) {
				if (structuralHelper == null) {
					this.structuralHelper = new ChildHelper<>(proxy);
					this.childNames = new ArrayList<>();
					
					synchronizer = new Synchronizer<>(
							notification -> {
								ChildData childData = notification.getData();

								new ChildMatch<Long>(childNames) {
									protected void insertChild(int index, Long childName) {
										Object childProxy = toolkit.getClientSession().create(childName);
										// child proxy will be null if the toolkit can't create it.
										if (childProxy != null) {
											structuralHelper.insertChild(index, childProxy);
										}
									}

									@Override
									protected void removeChildAt(int index) {
										Object child = structuralHelper.removeChildAt(index);
										toolkit.getClientSession().destroy(child);
									}
								}.match(childData.getChildObjectNames());
							});
					toolkit.registerNotificationListener(
							STRUCTURAL_NOTIF_TYPE, synchronizer);
					
					Notification<ChildData> lastNotification;
					try {
						lastNotification = toolkit.invoke(SYNCHRONIZE);
					} catch (Throwable e) {
						throw new UndeclaredThrowableException(e);
					}
					
					synchronizer.synchronize(lastNotification);
				}
			}
			
			structuralHelper.addStructuralListener(listener);
		}

		/*
		 * Remove a structural listener. From the Structural interface.
		 */
		public void removeStructuralListener(StructuralListener listener) {
			synchronized (this) {
				if (structuralHelper != null) {
					structuralHelper.removeStructuralListener(listener);
					
					if (structuralHelper.isNoListeners()) {
						toolkit.removeNotificationListener(STRUCTURAL_NOTIF_TYPE, synchronizer);
						synchronizer = null;
						structuralHelper = null;
					}
				}
			}
		}		
	}	
	
	static class ServerStructuralHelper implements ServerInterfaceHandler  {

		private final Structural structural;
		private final ServerSideToolkit toolkit;
		
		/** True if we've looped back onto an already exposed server. */
		private boolean duplicate;

		/** Child remote job nodes. */
		private final LinkedList<Long> children = new LinkedList<>();

		private final StructuralListener listener = new StructuralListener() {
			
			/*
			 * (non-Javadoc)
			 * 
			 * @see org.oddjob.structural.StructuralListener#childAdded(org.oddjob.structural.StructuralEvent)
			 */
			public void childAdded(final StructuralEvent e) {
				// stop events overlapping.
				final long child;
				Object childComponent = e.getChild();
				try {
					child = toolkit.getServerSession().createMBeanFor(
							childComponent, toolkit.getContext().addChild(childComponent));
				} catch (ServerLoopBackException e1) {
					logger.info("Server loopback detected.");
					duplicate = true;
					return;
				} catch (JMException e2) {
					logger.error("Failed creating child for [" + childComponent + "]", e2);
					return;
				}
				final int index = e.getIndex();
				
				ChildData newEvent;
				
				synchronized (children) {
					children.add(index, child);
					newEvent = new ChildData(
						children.toArray(new Long[0]));
				}
				
				final Notification<ChildData> notification =
					toolkit.createNotification(STRUCTURAL_NOTIF_TYPE, newEvent);
				
				toolkit.runSynchronized(() -> toolkit.sendNotification(notification));
				
				logger.debug("Child added [" + e.getChild().toString() + "], index [" + e.getIndex() + "]");
			}

			/*
			 * (non-Javadoc)
			 * 
			 * @see org.oddjob.structural.StructuralListener#childRemoved(org.oddjob.structural.StructuralEvent)
			 */
			public void childRemoved(final StructuralEvent e) {
				if (duplicate) {
					// a duplicate was never added.
					return;
				}
				final int index = e.getIndex();
				
				long child;
				ChildData newEvent;
				
				synchronized(children) {
					
					child = children.get(index);
					children.remove(index);
					newEvent = new ChildData(
							children.toArray(new Long[0]));
				}
				
				final Notification<ChildData> notification =
					toolkit.createNotification(STRUCTURAL_NOTIF_TYPE, newEvent);

				toolkit.runSynchronized(() -> toolkit.sendNotification(notification));
				
				try {
					toolkit.getServerSession().destroy(child);
				} catch (JMException e1) {
					logger.error("Failed destroying child [" + e.getChild() + "]", e1);
				}
				logger.debug("Child removed [" + e.getChild().toString() + "], index [" + e.getIndex() + "]");
			}
		};
		
		ServerStructuralHelper(Structural structural, 
				ServerSideToolkit ojmb) {
			this.structural = structural;
			this.toolkit = ojmb;
			structural.addStructuralListener(listener);
		}

		private Notification<ChildData> lastNotification() {
			final AtomicReference<Notification<ChildData>> lastNotifications = new AtomicReference<>();
			toolkit.runSynchronized(() -> {
				ChildData newEvent = new ChildData(
						children.toArray(new Long[0]));
				Notification<ChildData> notification =
					toolkit.createNotification(STRUCTURAL_NOTIF_TYPE, newEvent);
				lastNotifications.set(notification);
			});
			
			return lastNotifications.get();
		}

		public Object invoke(RemoteOperation<?> operation, Object[] params) throws MBeanException, ReflectionException {
		
			
			if (SYNCHRONIZE.equals(operation)) {
				return lastNotification();
			}

			throw new ReflectionException(
					new IllegalStateException("invoked for an unknown method."), 
							operation.toString());
		}
		
		public void destroy() {
			
			// Stop receiving event from Oddjob
			structural.removeStructuralListener(listener);
			
			// And use our listener to remove children.
			while (children.size() > 0) {
				final int index = children.size() - 1;
				StructuralEvent dummyEvent = new StructuralEvent(structural,
						new Object() {
							@Override
							public String toString() {
								return "Dummy Child " + index + 
										" of Destructing Parent [" +
										structural + "]";
							}
						}, index);
				listener.childRemoved(dummyEvent);
			}
		}

	}

	
	public static class ChildData implements Serializable {
		private static final long serialVersionUID = 2010062500L;
		
		private final Long[] remoteIds;
		
		public ChildData(Long[] remoteIds) {
			this.remoteIds = remoteIds;
		}
		
		public Long[] getChildObjectNames() {
			return remoteIds;
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
