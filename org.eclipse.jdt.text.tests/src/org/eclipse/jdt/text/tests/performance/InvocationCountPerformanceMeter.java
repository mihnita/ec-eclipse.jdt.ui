/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.text.tests.performance;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.sun.jdi.ClassType;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VirtualMachineManager;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventIterator;
import com.sun.jdi.event.EventQueue;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.EventRequest;

import org.eclipse.test.internal.performance.InternalDimensions;
import org.eclipse.test.internal.performance.InternalPerformanceMeter;
import org.eclipse.test.internal.performance.data.DataPoint;
import org.eclipse.test.internal.performance.data.Sample;
import org.eclipse.test.internal.performance.data.Scalar;
import org.eclipse.test.internal.performance.db.DB;
import org.eclipse.jdi.Bootstrap;

/**
 * To use this performance meter add the following VM arguments:
 * <code>-Xdebug -Xnoagent -Xrunjdwp:transport=dt_socket,address=PORT,suspend=n,server=y -Decilpse.perf.debugPort=PORT</code>
 * where PORT is the port on which the debugger will listen and connect to.
 * Because the performance meter uses the VM's debugging facility, it cannot be
 * debugged itself. A {@link org.eclipse.test.performance.Performance#getNullPerformanceMeter()}
 * could be used while debugging clients.
 * 
 * @since 3.1
 */
public class InvocationCountPerformanceMeter extends InternalPerformanceMeter {
	
	/**
	 * An event reader that continuously reads and handles events coming
	 * from the VM.
	 */
	public class EventReader implements Runnable {
		
		/** Event queue */
		private EventQueue fEventQueue;
		
		/** Background thread */
		private Thread fThread;
		
		/** <code>true</code> if the reader should stop */
		private boolean fIsStopping= false;
		
		/**
		 * Creates a new event reader that will read from the given
		 * event queue.
		 * 
		 * @param queue the event queue
		 */
		public EventReader(EventQueue queue) {
			fEventQueue= queue;
		}
		
		/**
		 * Start the thread that reads events.
		 */
		public void start() {
			fThread= new Thread(this);
			fThread.setDaemon(true);
			synchronized (fThread) {
				try {
					fThread.start();
					fThread.wait();
				} catch (InterruptedException x) {
					x.printStackTrace();
				}
			}
		}
		
		/**
		 * Tells the reader loop that it should stop.
		 */
		public void stop() {
			fIsStopping= true;
			if (fThread != null) {
				try {
					fThread.interrupt();
					fThread.join();
					fThread= null;
				} catch (InterruptedException x) {
					x.printStackTrace();
				}
			}
		}
		
		/**
		 * Continuously reads and handles events that are coming from
		 * the event queue.
		 */
		public void run() {
			try {
				synchronized (fThread) {
					enableBreakpoints();
					fThread.notifyAll();
				}
				while (!fIsStopping) {
					try {
						EventSet eventSet= fEventQueue.remove();
						
						EventIterator iterator= eventSet.eventIterator();
						while (iterator.hasNext()) {
							Event event= iterator.nextEvent();
							if (event instanceof BreakpointEvent)
								handleBreakpointEvent((BreakpointEvent) event);
							if (event instanceof VMDeathEvent)
								stop();
						}
						
						eventSet.resume();
					} catch (InterruptedException e) {
						if (fIsStopping)
							return;
						System.out.println("Event reader loop unexpectedly interrupted"); //$NON-NLS-1$
					} catch (VMDisconnectedException e) {
						System.out.println("VM unexpectedly disconnected"); //$NON-NLS-1$
						return;
					}
				}
			} finally {
				disableBreakpoints();
			}
		}

		/**
		 * Enables all breakpoint request.
		 */
		private void enableBreakpoints() {
			for (int i= 0; i < fBreakpointRequests.length; i++)
				fBreakpointRequests[i].enable();
		}

		/**
		 * Disables all breakpoint request.
		 */
		private void disableBreakpoints() {
			for (int i= 0; i < fBreakpointRequests.length; i++)
				fBreakpointRequests[i].disable();
		}

		/**
		 * Handles the given breakpoint event.
		 * 
		 * @param event the breakpoint event
		 */
		private void handleBreakpointEvent(BreakpointEvent event) {
			fInvocationCount++;
			try {
				ObjectReference thisObject= event.thread().frame(0).thisObject();
				Method method= event.location().method();
				fResults.update(method.declaringType().name() + "#" + method.name() + method.signature(), thisObject.referenceType().name() + " (id=" + thisObject.uniqueID() + ")");  //$NON-NLS-1$ //$NON-NLS-2$//$NON-NLS-3$
			} catch (Exception x) {
				x.printStackTrace();
			}
		}
	}
	
	/**
	 * Invocation count results.
	 */
	public static class Results {
		
		/** The result map */
		private Map fResultsMap= new HashMap();
		
		/**
		 * Updates the results for the given pair of keys.
		 * 
		 * @param key1 the first key
		 * @param key2 the second key
		 */
		public void update(Object key1, Object key2) {
			int value;
			Map results;
			if (fResultsMap.containsKey(key1)) {
				results= (Map) fResultsMap.get(key1);
				if (results.containsKey(key2))
					value= ((Integer) results.get(key2)).intValue();
				else
					value= 0;
			} else {
				results= new HashMap();
				fResultsMap.put(key1, results);
				value= 0;
			}
			results.put(key2, new Integer(value + 1));
		}
		
		/**
		 * Clears the results.
		 */
		public void clear() {
			fResultsMap.clear();
		}
		
		/**
		 * Prints the results.
		 */
		public void print() {
			for (Iterator iter= fResultsMap.keySet().iterator(); iter.hasNext();)
				print(iter.next());
		}
		
		/**
		 * Prints the results for the given first key.
		 * 
		 * @param key1 the first key
		 */
		public void print(Object key1) {
			System.out.println(key1.toString() + ":"); //$NON-NLS-1$
			Map results= ((Map) fResultsMap.get(key1));
			for (Iterator iter= results.keySet().iterator(); iter.hasNext();) {
				Object key2= iter.next();
				System.out.println("\t" + key2 + ": " + ((Integer) results.get(key2)).intValue()); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}
	
	/** System property that specifies the debugging port */
	public static final String DEBUG_PORT_PROPERTY= "eclipse.perf.debugPort"; //$NON-NLS-1$
	
	/** Default debugging port when the system property cannot be interpreted as an integer */
	public static final int DEBUG_PORT_DEFAULT= 7777;
	
	/** Debugging port */
	private static final int PORT= intValueOf(System.getProperty(DEBUG_PORT_PROPERTY), DEBUG_PORT_DEFAULT);
	
	/** Empty array of methods */
	private static final java.lang.reflect.Method[] NO_METHODS= new java.lang.reflect.Method[0];
	
	/** Empty array of constructors */
	private static final Constructor[] NO_CONSTRUCTORS= new Constructor[0];
	
	/** Results */
	private Results fResults= new Results();
	
	/** Virtual machine */
	private VirtualMachine fVM;
	
	/** Event reader */
	private EventReader fEventReader;
	
	/** Methods from which to count the invocations */
	private java.lang.reflect.Method[] fMethods;

	/** Constructors from which to count the invocations */
	private Constructor[] fConstructors;
	
	/** Timestamp */
	private long fStartTime;
	
	/** Total number of invocations */
	private long fInvocationCount;

	/** All breakpoint requests */
	private BreakpointRequest[] fBreakpointRequests;
	
	/**
	 * Initialize the performance meter to count the number of invocation of
	 * the given methods and constructors.
	 * 
	 * @param scenarioId the scenario id
	 * @param methods the methods
	 * @param constructors the constructors
	 */
	public InvocationCountPerformanceMeter(String scenarioId, java.lang.reflect.Method[] methods, Constructor[] constructors) {
		super(scenarioId);
		fMethods= methods;
		fConstructors= constructors;
		fStartTime= System.currentTimeMillis();
	}
	
	/**
	 * Initialize the performance meter to count the number of invocation of
	 * the given methods.
	 * 
	 * @param scenarioId the scenario id
	 * @param methods the methods
	 */
	public InvocationCountPerformanceMeter(String scenarioId, java.lang.reflect.Method[] methods) {
		this(scenarioId, methods, NO_CONSTRUCTORS);
	}
	
	/**
	 * Initialize the performance meter to count the number of invocation of
	 * the given constructors.
	 * 
	 * @param scenarioId the scenario id
	 * @param constructors the constructors
	 */
	public InvocationCountPerformanceMeter(String scenarioId, Constructor[] constructors) {
		this(scenarioId, NO_METHODS, constructors);
	}
	
	/*
	 * @see org.eclipse.test.performance.PerformanceMeter#start()
	 */
	public void start() {
		try {
			attach("localhost", PORT); //$NON-NLS-1$
			
			List requests= new ArrayList();
			for (int i= 0; i < fMethods.length; i++)
				requests.add(createBreakpointRequest(fMethods[i]));
			for (int i= 0; i < fConstructors.length; i++)
				requests.add(createBreakpointRequest(fConstructors[i]));
			fBreakpointRequests= (BreakpointRequest[]) requests.toArray(new BreakpointRequest[requests.size()]);
			
			fEventReader= new EventReader(fVM.eventQueue());
			fEventReader.start();
		} catch (IOException x) {
			x.printStackTrace();
		} catch (IllegalConnectorArgumentsException x) {
			x.printStackTrace();
		}
	}
	
	/*
	 * @see org.eclipse.test.performance.PerformanceMeter#stop()
	 */
	public void stop() {
		if (fEventReader != null) {
			fEventReader.stop();
			fEventReader= null;
		}
		
		if (fVM != null) {
			deleteBreakpointRequests();
			detach();
		}
	}
	
	/*
	 * @see org.eclipse.test.performance.PerformanceMeter#commit()
	 */
	public void commit() {
		super.commit();
		if (!DB.isActive() || System.getProperty(VERBOSE_PERFORMANCE_METER_PROPERTY) != null) {
			System.out.println("Detailed results:"); //$NON-NLS-1$
			fResults.print();
		}
	}
	
	/*
	 * @see org.eclipse.test.performance.PerformanceMeter#dispose()
	 */
	public void dispose() {
		super.dispose();
		if (fVM != null || fEventReader != null)
			stop();
		fResults= null;
		fMethods= null;
		fConstructors= null;
	}
	
	/*
	 * @see org.eclipse.test.internal.performance.InternalPerformanceMeter#getSample()
	 */
	public Sample getSample() {
		Map map= new HashMap(1);
		map.put(InternalDimensions.INVOCATION_COUNT, new Scalar(InternalDimensions.INVOCATION_COUNT, fInvocationCount));
		DataPoint[] dataPoints= new DataPoint[] { new DataPoint(AFTER, map) };
		return new Sample(getScenarioName(), fStartTime, null, dataPoints);
	}
	
	/**
	 * Attaches to the given host and port.
	 * 
	 * @param host the host
	 * @param port the port
	 * @throws IOException
	 * @throws IllegalConnectorArgumentsException
	 */
	private void attach(String host, int port) throws IOException, IllegalConnectorArgumentsException {
		VirtualMachineManager manager= Bootstrap.virtualMachineManager();
		List connectors= manager.attachingConnectors();
		AttachingConnector connector= (AttachingConnector) connectors.get(0);
		Map args= connector.defaultArguments();
		((Connector.Argument) args.get("port")).setValue(String.valueOf(port)); //$NON-NLS-1$
		((Connector.Argument) args.get("hostname")).setValue(host); //$NON-NLS-1$
		fVM= connector.attach(args);
	}
	
	/**
	 * Detaches from the VM.
	 */
	private void detach() {
		fVM.dispose();
		fVM= null;
	}
	
	/**
	 * Creates a breakpoint request on entry of the given method.
	 * 
	 * @param method the method
	 * @return the breakpoint request
	 */
	private BreakpointRequest createBreakpointRequest(java.lang.reflect.Method method) {
		return createBreakpointRequest(getMethod(method.getDeclaringClass().getName(), method.getName(), getJNISignature(method)));
	}
	
	/**
	 * Creates a breakpoint request on entry of the given constructor.
	 * 
	 * @param constructor the method
	 * @return the breakpoint request
	 */
	private BreakpointRequest createBreakpointRequest(Constructor constructor) {
		return createBreakpointRequest(getMethod(constructor.getDeclaringClass().getName(), "<init>", getJNISignature(constructor)));
	}
	
	/**
	 * Creates a breakpoint request on entry of the given method.
	 * 
	 * @param method the method
	 * @return the breakpoint request
	 */
	private BreakpointRequest createBreakpointRequest(Method method) {
		BreakpointRequest request= fVM.eventRequestManager().createBreakpointRequest(method.location());
		request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
		return request;
	}

	/**
	 * Returns the JDI method for the given class, name and signature.
	 * 
	 * @param className fully-qualified class name
	 * @param name method name
	 * @param signature JNI-style signature
	 * @return the JDI method
	 */
	private Method getMethod(String className, String name, String signature) {
		ClassType type= (ClassType) fVM.classesByName(className).get(0);
		return type.concreteMethodByName(name, signature);
	}
	
	/**
	 * Deletes all breakpoint requests.
	 */
	private void deleteBreakpointRequests() {
		fVM.eventRequestManager().deleteAllBreakpoints();
	}
	
	/**
	 * Returns the JNI-style signature of the given method. See
	 * http://java.sun.com/j2se/1.4.2/docs/guide/jpda/jdi/com/sun/jdi/doc-files/signature.html
	 * 
	 * @param method the method
	 * @return the JNI style signature
	 */
	private String getJNISignature(java.lang.reflect.Method method) {
		return getJNISignature(method.getParameterTypes()) + getJNISignature(method.getReturnType());
	}
	
	/**
	 * Returns the JNI-style signature of the given constructor. See
	 * http://java.sun.com/j2se/1.4.2/docs/guide/jpda/jdi/com/sun/jdi/doc-files/signature.html
	 * 
	 * @param constructor the constructor
	 * @return the JNI style signature
	 */
	private String getJNISignature(Constructor constructor) {
		return getJNISignature(constructor.getParameterTypes()) + "V";
	}
	
	/**
	 * Returns the JNI-style signature of the given parameter types. See
	 * http://java.sun.com/j2se/1.4.2/docs/guide/jpda/jdi/com/sun/jdi/doc-files/signature.html
	 * 
	 * @param paramTypes the parameter types
	 * @return the JNI style signature
	 */
	private String getJNISignature(Class[] paramTypes) {
		StringBuffer signature= new StringBuffer();
		signature.append('(');
		for (int i = 0; i < paramTypes.length; ++i)
			signature.append(getJNISignature(paramTypes[i]));
		signature.append(')');
		return signature.toString();
	}

	/**
	 * Returns the JNI-style signature of the given class. See
	 * http://java.sun.com/j2se/1.4.2/docs/guide/jpda/jdi/com/sun/jdi/doc-files/signature.html
	 * 
	 * @param clazz the class
	 * @return the JNI style signature
	 */
	private String getJNISignature(Class clazz) {
		String qualifiedName= getName(clazz);
		StringBuffer signature= new StringBuffer();
		
		int index= qualifiedName.indexOf('[') + 1;
		while (index > 0) {
			index= qualifiedName.indexOf('[', index) + 1;
			signature.append('[');
		}
		
		int nameEndOffset= qualifiedName.indexOf('[');
		if (nameEndOffset < 0)
			nameEndOffset= qualifiedName.length();
		
		// Check for primitive types
		String name= qualifiedName.substring(0, nameEndOffset);
		if (name.equals("byte")) { //$NON-NLS-1$
			signature.append('B');
			return signature.toString();
		} else if (name.equals("boolean")) { //$NON-NLS-1$
			signature.append('Z');
			return signature.toString();
		} else if (name.equals("int")) { //$NON-NLS-1$
			signature.append('I');
			return signature.toString();
		} else if (name.equals("double")) { //$NON-NLS-1$
			signature.append('D');
			return signature.toString();
		} else if (name.equals("short")) { //$NON-NLS-1$
			signature.append('S');
			return signature.toString();
		} else if (name.equals("char")) { //$NON-NLS-1$
			signature.append('C');
			return signature.toString();
		} else if (name.equals("long")) { //$NON-NLS-1$
			signature.append('J');
			return signature.toString();
		} else if (name.equals("float")) { //$NON-NLS-1$
			signature.append('F');
			return signature.toString();
		} else if (name.equals("void")) { //$NON-NLS-1$
			signature.append('V');
			return signature.toString();
		}
		
		// Class type
		signature.append('L');
		signature.append(name.replace('.','/'));
		signature.append(';');
		return signature.toString();
	}
	
	/**
	 * Returns the given class' name
	 * 
	 * @param clazz the class
	 * @return the name
	 */
	private String getName(Class clazz) {
		if (clazz.isArray())
			return getName(clazz.getComponentType()) + "[]"; //$NON-NLS-1$
		return clazz.getName();
	}
	
	/**
	 * Returns the integer value of the given string unless the string
	 * cannot be interpreted as such, in this case the given default is
	 * returned.
	 * 
	 * @param stringValue the string to be interpreted as integer
	 * @param defaultValue the default integer value
	 * @return the integer value
	 */
	private static int intValueOf(String stringValue, int defaultValue) {
		try {
			if (stringValue != null)
				return Integer.valueOf(stringValue).intValue();
		} catch (NumberFormatException e) {
			// use default
		}
		return defaultValue;
	}
}
