package com.lody.virtual.server.am;

import android.app.IServiceConnection;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;

import com.lody.virtual.helper.utils.IsolatedServiceRouting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class ServiceRecord extends Binder {
	private static final AtomicLong NEXT_GENERATION = new AtomicLong(1);

	public final List<IntentBindRecord> bindings = new ArrayList<>();
	public final long generation;
	public long activeSince;
	public long lastActivityTime;
	public ServiceInfo serviceInfo;
	private String serviceInstanceName;
	public int startId;
	public ProcessRecord process;
	public int foregroundId;
	public Notification foregroundNoti;
	private boolean createScheduled;
	private boolean retired;
	private ConnectionDeathCallback connectionDeathCallback;

	public ServiceRecord() {
		this(NEXT_GENERATION.getAndIncrement());
	}

	ServiceRecord(long generation) {
		this.generation = generation;
	}

	void setServiceInstanceName(String instanceName) {
		serviceInstanceName = IsolatedServiceRouting.normalizeInstanceName(instanceName);
	}

	boolean matchesServiceInstanceName(String instanceName) {
		return java.util.Objects.equals(serviceInstanceName,
				IsolatedServiceRouting.normalizeInstanceName(instanceName));
	}

	/** Returns true only for the first create dispatch of a live service generation. */
	public synchronized boolean markCreateScheduled() {
		if (retired || createScheduled) {
			return false;
		}
		createScheduled = true;
		return true;
	}

	public synchronized boolean isCreateScheduled() {
		return createScheduled;
	}

	public synchronized boolean isRetired() {
		return retired;
	}

	public synchronized boolean acceptsCallback(long expectedGeneration) {
		return !retired && generation == expectedGeneration;
	}

	/** Retires this token and prevents pending or late bind callbacks from being delivered. */
	public boolean retire() {
		List<IntentBindRecord> snapshot;
		synchronized (bindings) {
			synchronized (this) {
				if (retired) {
					return false;
				}
				retired = true;
				snapshot = new ArrayList<>(bindings);
			}
		}
		for (IntentBindRecord binding : snapshot) {
			binding.retire();
		}
		return true;
	}

	public void setConnectionDeathCallback(ConnectionDeathCallback callback) {
		synchronized (bindings) {
			synchronized (this) {
				connectionDeathCallback = callback;
			}
			for (IntentBindRecord binding : bindings) {
				binding.setConnectionDeathCallback(callback);
			}
		}
	}

	public interface ConnectionDeathCallback {
		void onConnectionDied(IntentBindRecord binding, IServiceConnection connection,
				boolean lastConnection);
	}

	public boolean containConnection(IServiceConnection connection) {
		synchronized (bindings) {
			for (IntentBindRecord record : bindings) {
				if (record.containConnection(connection)) {
					return true;
				}
			}
		}
		return false;
	}

	public int getClientCount() {
		synchronized (bindings) {
			return bindings.size();
		}
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

	/**
	 * Clears the service's started state when stopSelf/stopService is requested.
	 *
	 * <p>A bound service may clear its started state while clients remain connected. Android keeps
	 * that service alive until the final client unbinds, so callers must check
	 * {@link #hasActiveConnections()} before retiring this record.</p>
	 */
	boolean clearStartedState(int expectedStartId) {
		synchronized (this) {
			if (expectedStartId != -1 && startId != expectedStartId) {
				return false;
			}
			startId = 0;
			return true;
		}
	}

	boolean hasActiveConnections() {
		return getConnectionCount() > 0;
	}


	IntentBindRecord peekBinding(Intent service) {
		synchronized (bindings) {
			if (isRetired()) {
				return null;
			}
			for (IntentBindRecord bindRecord : bindings) {
				if (bindRecord.intent.filterEquals(service)) {
					return bindRecord;
				}
			}
		}
		return null;
	}

	IntentBindRecord peekBinding(IBinder bindToken) {
		if (bindToken == null) {
			return null;
		}
		synchronized (bindings) {
			if (isRetired()) {
				return null;
			}
			for (IntentBindRecord bindRecord : bindings) {
				if (bindRecord.matchesBindToken(bindToken)) {
					return bindRecord;
				}
			}
		}
		return null;
	}

	IntentBindRecord peekUnbindInFlight() {
		synchronized (bindings) {
			if (isRetired()) {
				return null;
			}
			for (IntentBindRecord bindRecord : bindings) {
				if (bindRecord.isUnbindInFlight()) {
					return bindRecord;
				}
			}
		}
		return null;
	}

	IntentBindRecord addToBoundIntent(Intent intent, IServiceConnection connection) {
		synchronized (bindings) {
			if (isRetired()) {
				return null;
			}
			for (IntentBindRecord bindRecord : bindings) {
				if (bindRecord.intent.filterEquals(intent)) {
					bindRecord.addConnection(connection);
					return bindRecord;
				}
			}
			IntentBindRecord record = new IntentBindRecord(generation);
			record.intent = intent;
			record.setConnectionDeathCallback(connectionDeathCallback);
			record.addConnection(connection);
			bindings.add(record);
			return record;
		}
	}

	public static class IntentBindRecord {
		public enum UnbindResult {
			IGNORED,
			NO_CLIENTS,
			REBIND,
			BIND
		}

		public  final List<IServiceConnection> connections = Collections.synchronizedList(new ArrayList<IServiceConnection>());
		public IBinder binder;
		private final IBinder bindToken = new Binder();
		private final long generation;
		private long bindSequence;
		Intent intent;
		private boolean bindRequested;
		private boolean doRebind = false;
		private boolean unbindInFlight;
		private volatile boolean retired;
		private volatile ConnectionDeathCallback connectionDeathCallback;
		private final Map<IBinder, DeathRecipient> deathRecipients = new IdentityHashMap<>();

		public IntentBindRecord() {
			this(0);
		}

		IntentBindRecord(long generation) {
			this.generation = generation;
		}

		public long getGeneration() {
			return generation;
		}

		public IBinder getBindToken() {
			return bindToken;
		}

		public boolean matchesBindToken(IBinder candidate) {
			return candidate != null && bindToken == candidate;
		}

		/** Returns the sequence for the next bind or rebind dispatch of this binding. */
		public synchronized long nextBindSequence() {
			return ++bindSequence;
		}

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
				if (retired || containConnection(connection)) {
					return;
				}
				IBinder connectionBinder = connection.asBinder();
				DeathRecipient recipient = new DeathRecipient(this, connection);
				try {
					connectionBinder.linkToDeath(recipient, 0);
				} catch (RemoteException e) {
					return;
				}
				connections.add(connection);
				deathRecipients.put(connectionBinder, recipient);
			}
		}

		public boolean removeConnection(IServiceConnection connection) {
			boolean removed = false;
			synchronized (connections) {
				IBinder connectionBinder = connection.asBinder();
				Iterator<IServiceConnection> iterator = connections.iterator();
				while (iterator.hasNext()) {
					IServiceConnection conn = iterator.next();
					if (conn.asBinder() == connectionBinder) {
						iterator.remove();
						removed = true;
					}
				}
				DeathRecipient recipient = deathRecipients.remove(connectionBinder);
				if (recipient != null) {
					connectionBinder.unlinkToDeath(recipient, 0);
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
			if (retired || unbindInFlight || bindRequested
					|| (binder != null && binder.isBinderAlive())) {
				return false;
			}
			bindRequested = true;
			return true;
		}

		public synchronized void bindRequestFailed() {
			bindRequested = false;
		}

		/** True while a live connection is waiting for its single onBind dispatch. */
		public synchronized boolean shouldDispatchBind() {
			return !retired && !unbindInFlight && bindRequested
					&& binder == null && hasConnections();
		}

		public synchronized boolean hasPublishedBinder() {
			return !retired && binder != null && binder.isBinderAlive();
		}

		/** Marks the one guest onUnbind call that follows the final client leaving. */
		public synchronized boolean beginUnbindIfNeeded() {
			if (retired || unbindInFlight || binder == null || !binder.isBinderAlive()) {
				return false;
			}
			unbindInFlight = true;
			return true;
		}

		public synchronized boolean isUnbindInFlight() {
			return unbindInFlight;
		}

		/** Completes onUnbind and tells the caller which guest callback is now required. */
		public synchronized UnbindResult finishUnbind(boolean wantsRebind) {
			if (retired || !unbindInFlight) {
				return UnbindResult.IGNORED;
			}
			unbindInFlight = false;
			if (!hasConnections()) {
				doRebind = wantsRebind;
				if (!wantsRebind) {
					binder = null;
				}
				return UnbindResult.NO_CLIENTS;
			}
			if (wantsRebind) {
				doRebind = false;
				return UnbindResult.REBIND;
			}
			binder = null;
			bindRequested = true;
			return UnbindResult.BIND;
		}

		/** Cancels an onBind that has not been published yet. */
		public synchronized boolean cancelPendingBind() {
			if (!bindRequested || binder != null) {
				return false;
			}
			bindRequested = false;
			return true;
		}

		/** Cancels a pending onBind only after its final client has gone away. */
		public synchronized boolean cancelPendingBindIfNoConnections() {
			synchronized (connections) {
				if (!connections.isEmpty()) {
					return false;
				}
				return cancelPendingBind();
			}
		}

		public synchronized List<IServiceConnection> publish(IBinder service) {
			return publish(generation, service);
		}

		/** Publishes only when the callback belongs to this live service generation. */
		public synchronized List<IServiceConnection> publish(long expectedGeneration, IBinder service) {
			if (retired || generation != expectedGeneration || !bindRequested) {
				return Collections.emptyList();
			}
			binder = service;
			bindRequested = false;
			return snapshotConnections();
		}

		/** Consumes a completed onUnbind(true) result so onRebind is scheduled once. */
		public synchronized boolean consumeDoRebind() {
			if (retired || !doRebind) {
				return false;
			}
			doRebind = false;
			return true;
		}

		public synchronized void setDoRebind(boolean doRebind) {
			if (!retired) {
				this.doRebind = doRebind;
			}
		}

		public synchronized boolean isRetired() {
			return retired;
		}

		public synchronized void setConnectionDeathCallback(ConnectionDeathCallback callback) {
			connectionDeathCallback = callback;
		}

		public void retire() {
			Map<IBinder, DeathRecipient> recipients;
			synchronized (this) {
				if (retired) {
					return;
				}
				retired = true;
				bindRequested = false;
				doRebind = false;
				unbindInFlight = false;
				binder = null;
				connectionDeathCallback = null;
				synchronized (connections) {
					recipients = new IdentityHashMap<>(deathRecipients);
					deathRecipients.clear();
					connections.clear();
				}
			}
			for (Map.Entry<IBinder, DeathRecipient> entry : recipients.entrySet()) {
				entry.getKey().unlinkToDeath(entry.getValue(), 0);
			}
		}

		private void connectionDied(IServiceConnection connection) {
			boolean lastConnection = removeConnectionAndCheckIfLast(connection);
			ConnectionDeathCallback callback;
			synchronized (this) {
				callback = connectionDeathCallback;
			}
			if (callback != null) {
				callback.onConnectionDied(this, connection, lastConnection);
			}
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
			bindRecord.connectionDied(connection);
		}
	}

}
