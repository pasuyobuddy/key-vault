package com.pasuyolabs.keyvault;

import android.os.Bundle;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VaultBridgePlugin.class);   // our own native bridge (vault copy, apps, fingerprint, Autofill)
        super.onCreate(savedInstanceState);
        // no screenshots, no screen recording, blank preview in Recent apps
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
    }
}
