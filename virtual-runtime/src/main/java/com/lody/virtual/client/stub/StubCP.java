package com.lody.virtual.client.stub;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.IBinder;
import android.os.Process;

import com.lody.virtual.client.StubProcessOwner;
import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.compat.BundleCompat;

/**
 * @author Lody
 *
 */
public class StubCP extends ContentProvider {

	@Override
	public boolean onCreate() {
		return true;
	}

	@Override
	public Bundle call(String method, String arg, Bundle extras) {
		if (StubProcessContract.METHOD_INIT_PROCESS.equals(method)) {
			return initProcess(extras);
		}
		if (StubProcessContract.METHOD_QUERY_OWNER.equals(method)) {
			return queryProcessOwner();
		}
		return null;
	}

	private Bundle initProcess(Bundle extras) {
		ConditionVariable lock = VirtualCore.get().getInitLock();
		if (lock != null) {
			lock.block();
		}
		VClientImpl client = VClientImpl.get();
		if (extras == null) {
			return createResponse(client, false, StubProcessOwner.REASON_INVALID_REQUEST,
					client.getProcessOwner());
		}
		IBinder token = BundleCompat.getBinder(extras, StubProcessContract.KEY_SERVER_TOKEN);
		int vuid = extras.getInt(StubProcessContract.KEY_VUID);
		String packageName = extras.getString(StubProcessContract.KEY_PACKAGE_NAME);
		String processName = extras.getString(StubProcessContract.KEY_PROCESS_NAME);
		long generation = extras.getLong(StubProcessContract.KEY_GENERATION, 0L);
		StubProcessOwner.ClaimResult result = client.claimProcess(token, vuid, packageName,
				processName, generation);
		return createResponse(client, result.isAccepted(), result.getReason(),
				result.getCurrentIdentity());
	}

	private Bundle queryProcessOwner() {
		VClientImpl client = VClientImpl.get();
		StubProcessOwner.Identity identity = client.getProcessOwner();
		return createResponse(client, true,
				identity == null ? StubProcessContract.REASON_QUERY_EMPTY
						: StubProcessContract.REASON_QUERY_CURRENT,
				identity);
	}

	private Bundle createResponse(VClientImpl client, boolean accepted, String reason,
			StubProcessOwner.Identity identity) {
		Bundle response = new Bundle();
		response.putBoolean(StubProcessContract.KEY_ACCEPTED, accepted);
		response.putString(StubProcessContract.KEY_REASON, reason);
		response.putBoolean(StubProcessContract.KEY_HAS_OWNER, identity != null);
		response.putBoolean(StubProcessContract.KEY_GUEST_BOUND, client.isGuestBindingStarted());
		BundleCompat.putBinder(response, StubProcessContract.KEY_CLIENT, client.asBinder());
		response.putInt(StubProcessContract.KEY_PID, Process.myPid());
		if (identity != null) {
			response.putInt(StubProcessContract.KEY_VUID, identity.getVuid());
			response.putString(StubProcessContract.KEY_PACKAGE_NAME, identity.getPackageName());
			response.putString(StubProcessContract.KEY_PROCESS_NAME, identity.getProcessName());
			response.putLong(StubProcessContract.KEY_GENERATION, identity.getGeneration());
			BundleCompat.putBinder(response, StubProcessContract.KEY_SERVER_TOKEN,
					(IBinder) identity.getServerToken());
		}
		return response;
	}

	@Override
	public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		return null;
	}

	@Override
	public String getType(Uri uri) {
		return null;
	}

	@Override
	public Uri insert(Uri uri, ContentValues values) {
		return null;
	}

	@Override
	public int delete(Uri uri, String selection, String[] selectionArgs) {
		return 0;
	}

	@Override
	public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		return 0;
	}


	public static class C0 extends StubCP {
	}

	public static class C1 extends StubCP {
	}

	public static class C2 extends StubCP {
	}

	public static class C3 extends StubCP {
	}

	public static class C4 extends StubCP {
	}

	public static class C5 extends StubCP {
	}

	public static class C6 extends StubCP {
	}

	public static class C7 extends StubCP {
	}

	public static class C8 extends StubCP {
	}

	public static class C9 extends StubCP {
	}

	public static class C10 extends StubCP {
	}

	public static class C11 extends StubCP {
	}

	public static class C12 extends StubCP {
	}

	public static class C13 extends StubCP {
	}

	public static class C14 extends StubCP {
	}

	public static class C15 extends StubCP {
	}

	public static class C16 extends StubCP {
	}

	public static class C17 extends StubCP {
	}

	public static class C18 extends StubCP {
	}

	public static class C19 extends StubCP {
	}

	public static class C20 extends StubCP {
	}

	public static class C21 extends StubCP {
	}

	public static class C22 extends StubCP {
	}

	public static class C23 extends StubCP {
	}

	public static class C24 extends StubCP {
	}

	public static class C25 extends StubCP {
	}

	public static class C26 extends StubCP {
	}

	public static class C27 extends StubCP {
	}

	public static class C28 extends StubCP {
	}

	public static class C29 extends StubCP {
	}

	public static class C30 extends StubCP {
	}

	public static class C31 extends StubCP {
	}

	public static class C32 extends StubCP {
	}

	public static class C33 extends StubCP {
	}

	public static class C34 extends StubCP {
	}

	public static class C35 extends StubCP {
	}

	public static class C36 extends StubCP {
	}

	public static class C37 extends StubCP {
	}

	public static class C38 extends StubCP {
	}

	public static class C39 extends StubCP {
	}

	public static class C40 extends StubCP {
	}

	public static class C41 extends StubCP {
	}

	public static class C42 extends StubCP {
	}

	public static class C43 extends StubCP {
	}

	public static class C44 extends StubCP {
	}

	public static class C45 extends StubCP {
	}

	public static class C46 extends StubCP {
	}

	public static class C47 extends StubCP {
	}

	public static class C48 extends StubCP {
	}

	public static class C49 extends StubCP {
	}

	public static class C50 extends StubCP {
	}

	public static class C51 extends StubCP {
	}

	public static class C52 extends StubCP {
	}

	public static class C53 extends StubCP {
	}

	public static class C54 extends StubCP {
	}

	public static class C55 extends StubCP {
	}

	public static class C56 extends StubCP {
	}

	public static class C57 extends StubCP {
	}

	public static class C58 extends StubCP {
	}

	public static class C59 extends StubCP {
	}

	public static class C60 extends StubCP {
	}

	public static class C61 extends StubCP {
	}

	public static class C62 extends StubCP {
	}

	public static class C63 extends StubCP {
	}

	public static class C64 extends StubCP {
	}

	public static class C65 extends StubCP {
	}

	public static class C66 extends StubCP {
	}

	public static class C67 extends StubCP {
	}

	public static class C68 extends StubCP {
	}

	public static class C69 extends StubCP {
	}

	public static class C70 extends StubCP {
	}

	public static class C71 extends StubCP {
	}

	public static class C72 extends StubCP {
	}

	public static class C73 extends StubCP {
	}

	public static class C74 extends StubCP {
	}

	public static class C75 extends StubCP {
	}

	public static class C76 extends StubCP {
	}

	public static class C77 extends StubCP {
	}

	public static class C78 extends StubCP {
	}

	public static class C79 extends StubCP {
	}

	public static class C80 extends StubCP {
	}

	public static class C81 extends StubCP {
	}

	public static class C82 extends StubCP {
	}

	public static class C83 extends StubCP {
	}

	public static class C84 extends StubCP {
	}

	public static class C85 extends StubCP {
	}

	public static class C86 extends StubCP {
	}

	public static class C87 extends StubCP {
	}

	public static class C88 extends StubCP {
	}

	public static class C89 extends StubCP {
	}

	public static class C90 extends StubCP {
	}

	public static class C91 extends StubCP {
	}

	public static class C92 extends StubCP {
	}

	public static class C93 extends StubCP {
	}

	public static class C94 extends StubCP {
	}

	public static class C95 extends StubCP {
	}

	public static class C96 extends StubCP {
	}

	public static class C97 extends StubCP {
	}

	public static class C98 extends StubCP {
	}

	public static class C99 extends StubCP {
	}


}
