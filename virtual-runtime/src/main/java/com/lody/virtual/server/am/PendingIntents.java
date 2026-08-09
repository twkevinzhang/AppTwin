package com.lody.virtual.server.am;

import android.os.IBinder;
import android.os.RemoteException;

import com.lody.virtual.remote.PendingIntentData;

import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;


/**
 * @author Lody
 */
public final class PendingIntents {

    private final Map<IBinder, PendingIntentData> mLruHistory = new HashMap<>();
    private final Map<IBinder, Integer> mUserIds = new HashMap<>();

    final PendingIntentData getPendingIntent(IBinder binder) {
        synchronized (mLruHistory) {
            return mLruHistory.get(binder);
        }
    }

    final void addPendingIntent(final IBinder binder, String creator, int userId) {
        synchronized (mLruHistory) {
            try {
                binder.linkToDeath(new IBinder.DeathRecipient() {
                    @Override
                    public void binderDied() {
                        binder.unlinkToDeath(this, 0);
                        synchronized (mLruHistory) {
                            mLruHistory.remove(binder);
                            mUserIds.remove(binder);
                        }
                    }
                }, 0);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            PendingIntentData pendingIntentData = mLruHistory.get(binder);
            if (pendingIntentData == null) {
                mLruHistory.put(binder, new PendingIntentData(creator, binder));
            } else {
                pendingIntentData.creator = creator;
            }
            mUserIds.put(binder, userId);
        }
    }

    final void removePendingIntent(IBinder binder) {
        synchronized (mLruHistory) {
            mLruHistory.remove(binder);
            mUserIds.remove(binder);
        }
    }

    final void clearPackageUser(String packageName, int userId) {
        synchronized (mLruHistory) {
            Iterator<Map.Entry<IBinder, PendingIntentData>> iterator =
                    mLruHistory.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<IBinder, PendingIntentData> entry = iterator.next();
                Integer ownerUserId = mUserIds.get(entry.getKey());
                PendingIntentData data = entry.getValue();
                if (ownerUserId != null && ownerUserId == userId
                        && packageName.equals(data.creator)) {
                    if (data.pendingIntent != null) data.pendingIntent.cancel();
                    mUserIds.remove(entry.getKey());
                    iterator.remove();
                }
            }
        }
    }

    final void clearUser(int userId) {
        synchronized (mLruHistory) {
            Iterator<Map.Entry<IBinder, PendingIntentData>> iterator =
                    mLruHistory.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<IBinder, PendingIntentData> entry = iterator.next();
                if (belongsToUser(mUserIds.get(entry.getKey()), userId)) {
                    PendingIntentData data = entry.getValue();
                    if (data.pendingIntent != null) data.pendingIntent.cancel();
                    mUserIds.remove(entry.getKey());
                    iterator.remove();
                }
            }
        }
    }

    static boolean belongsToUser(Integer ownerUserId, int requestedUserId) {
        return ownerUserId != null && ownerUserId == requestedUserId;
    }

    final boolean hasUser(int userId) {
        synchronized (mLruHistory) {
            for (IBinder binder : mLruHistory.keySet()) {
                if (belongsToUser(mUserIds.get(binder), userId)) return true;
            }
            return false;
        }
    }

    final boolean hasPackageUser(String packageName, int userId) {
        synchronized (mLruHistory) {
            for (Map.Entry<IBinder, PendingIntentData> entry : mLruHistory.entrySet()) {
                Integer ownerUserId = mUserIds.get(entry.getKey());
                if (ownerUserId != null && ownerUserId == userId
                        && packageName.equals(entry.getValue().creator)) {
                    return true;
                }
            }
            return false;
        }
    }
}
