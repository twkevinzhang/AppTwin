package com.lody.virtual.server.am;

import android.app.IServiceConnection;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class ServiceRecord extends Binder {
	public final List<IntentBindRecord> bindings = new ArrayList<>();
	public long activeSince;
	public long lastActivityTime;
	public ServiceInfo serviceInfo;
	public int startId;
	public ProcessRecord process;
	public int foregroundId;
	public Notification foregroundNoti;

	public boolean containConnection(IServiceConnection connection) {
		for (IntentBindRecord record : bindings) {
			if (record.containConnection(connection)) {
				return true;
			}
		}
		return false;
	}

	public int getClientCount() {
		return bindings.size();
	}


	int getConnectionCount() {
		int count = 0;
		synchronized (bindings) {
			for (IntentBindRecord record : bindings) {
				count += record.connections.size();
			}
		}
		return count;
	}


	IntentBindRecord peekBinding(Intent service) {
		synchronized (bindings) {
			for (IntentBindRecord bindRecord : bindings) {
				if (bindRecord.intent.filterEquals(service)) {
					return bindRecord;
				}
			}
		}
		return null;
	}

	IntentBindRecord addToBoundIntent(Intent intent, IServiceConnection connection) {
		synchronized (bindings) {
			for (IntentBindRecord bindRecord : bindings) {
				if (bindRecord.intent.filterEquals(intent)) {
					bindRecord.addConnection(connection);
					return bindRecord;
				}
			}
			IntentBindRecord record = new IntentBindRecord();
			record.intent = intent;
			record.addConnection(connection);
			bindings.add(record);
			return record;
		}
	}

	public static class IntentBindRecord {
		public  final List<IServiceConnection> connections = Collections.synchronizedList(new ArrayList<IServiceConnection>());
		public IBinder binder;
		Intent intent;
		private boolean bindRequested;
		private boolean doRebind = false;

		public boolean containConnection(IServiceConnection connection) {
			synchronized (connections) {
				for (IServiceConnection con : connections) {
					if (con.asBinder() == connection.asBinder()) {
						return true;
					}
				}
			}
			return false;
		}

		public void addConnection(IServiceConnection connection) {
			synchronized (connections) {
				if (!containConnection(connection)) {
					connections.add(connection);
					try {
						connection.asBinder().linkToDeath(new DeathRecipient(this, connection), 0);
					} catch (RemoteException e) {
						e.printStackTrace();
					}
				}
			}
		}

		public boolean removeConnection(IServiceConnection connection) {
			boolean removed = false;
			synchronized (connections) {
				Iterator<IServiceConnection> iterator = connections.iterator();
				while (iterator.hasNext()) {
					IServiceConnection conn = iterator.next();
					if (conn.asBinder() == connection.asBinder()) {
						iterator.remove();
						removed = true;
					}
				}
			}
			return removed;
		}

		/** Removes a connection and reports whether it was the final client atomically. */
		public boolean removeConnectionAndCheckIfLast(IServiceConnection connection) {
			synchronized (connections) {
				boolean removed = removeConnection(connection);
				return removed && connections.isEmpty();
			}
		}

		public boolean hasConnections() {
			synchronized (connections) {
				return !connections.isEmpty();
			}
		}

		public List<IServiceConnection> snapshotConnections() {
			synchronized (connections) {
				return new ArrayList<>(connections);
			}
		}

		/** Returns true once while an unpublished binding needs onBind scheduled. */
		public synchronized boolean requestBindIfNeeded() {
			if (bindRequested || (binder != null && binder.pingBinder())) {
				return false;
			}
			bindRequested = true;
			return true;
		}

		public synchronized void bindRequestFailed() {
			bindRequested = false;
		}

		public synchronized List<IServiceConnection> publish(IBinder service) {
			binder = service;
			bindRequested = false;
			return snapshotConnections();
		}

		/** Consumes a completed onUnbind(true) result so onRebind is scheduled once. */
		public synchronized boolean consumeDoRebind() {
			if (!doRebind) {
				return false;
			}
			doRebind = false;
			return true;
		}

		public synchronized void setDoRebind(boolean doRebind) {
			this.doRebind = doRebind;
		}
	}

	private static class DeathRecipient implements IBinder.DeathRecipient {

		private final IntentBindRecord bindRecord;
		private final IServiceConnection connection;

		private DeathRecipient(IntentBindRecord bindRecord, IServiceConnection connection) {
			this.bindRecord = bindRecord;
			this.connection = connection;
		}

		@Override
		public void binderDied() {
			bindRecord.removeConnection(connection);
			connection.asBinder().unlinkToDeath(this, 0);
		}
	}

}
