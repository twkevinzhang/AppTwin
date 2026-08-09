package org.apptwin.gms.fixture;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.OnAccountsUpdateListener;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class MainActivity extends Activity {
    private LinearLayout resultsContainer;
    private ProbeStore store;
    private AccountManager accountManager;
    private OnAccountsUpdateListener accountListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ProbeStore(this);
        setContentView(buildContent());
        registerAccountListenerProbe();
        new LocalProbeRunner(this).runAll();
        renderResults();
    }

    @Override
    protected void onDestroy() {
        if (accountManager != null && accountListener != null) {
            accountManager.removeOnAccountsUpdatedListener(accountListener);
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (resultsContainer != null) {
            renderResults();
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        root.setPadding(padding, padding, padding, padding);
        scroll.addView(root);

        TextView title = text("AppTwin · GMS capability fixture", 22, Color.BLACK);
        root.addView(title);
        TextView boundary = text(
                "Local/ASUS evidence only. External FCM, Maps, Sign-In, Cast and Nearby flows "
                        + "remain untested. Billing and Integrity are fixed unsupported.",
                14, 0xFF555B66);
        boundary.setPadding(0, dp(8), 0, dp(16));
        root.addView(boundary);

        TextView sentinel = text("Group sentinel\n" + store.getOrCreateSentinel(), 13, 0xFF18203A);
        sentinel.setBackgroundColor(0xFFF0F3FA);
        sentinel.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.addView(sentinel);

        Button rerun = new Button(this);
        rerun.setText("Run local probes");
        rerun.setOnClickListener(view -> {
            new LocalProbeRunner(this).runAll();
            renderResults();
        });
        root.addView(rerun);

        resultsContainer = new LinearLayout(this);
        resultsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultsContainer);
        return scroll;
    }

    private void renderResults() {
        resultsContainer.removeAllViews();
        Map<ProbeId, ProbeResult> results = store.readAll();
        for (ProbeId id : ProbeId.values()) {
            ProbeResult result = results.get(id);
            String body = id.name() + "\n"
                    + (result == null ? "NOT_RUN" : result.status.name()) + "\n"
                    + (result == null ? "No persisted evidence" : result.evidence);
            TextView row = text(body, 13, Color.BLACK);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackgroundColor(result != null && result.status == ProbeStatus.UNSUPPORTED
                    ? 0xFFFFEDEC : 0xFFF7F8FB);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(8);
            resultsContainer.addView(row, params);
        }
    }

    /** Records only callback count/effective account count; no account identity leaves the guest. */
    private void registerAccountListenerProbe() {
        accountManager = getSystemService(AccountManager.class);
        accountListener = this::recordAccountListenerEvent;
        accountManager.addOnAccountsUpdatedListener(accountListener, null, true);
    }

    private synchronized void recordAccountListenerEvent(Account[] accounts) {
        File output = new File(getFilesDir(), "account-listener-events.txt");
        String event = System.currentTimeMillis() + ":" + accounts.length + "\n";
        try (FileOutputStream stream = new FileOutputStream(output, true)) {
            stream.write(event.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        } catch (Exception error) {
            throw new IllegalStateException("Unable to persist account-listener evidence", error);
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTextIsSelectable(true);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
