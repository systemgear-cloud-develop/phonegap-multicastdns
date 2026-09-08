package com.koalasafe.cordova.plugin.multicastdns;

import android.content.Context;
import org.apache.cordova.*;
import org.json.JSONArray;
import org.json.JSONException;

public class MulticastDNSPlugin extends CordovaPlugin {
	private static final String ACTION_QUERY = "query";

	@Override
	public boolean execute(String action, JSONArray data, CallbackContext callbackContext) throws JSONException {
		try {
			if (action.equals(ACTION_QUERY)) {
				final Context context = this.cordova.getActivity().getApplicationContext();
				final String host = data.getString(0);
				final CallbackContext cb = callbackContext;
				cordova.getThreadPool().execute(new Runnable() {
					public void run() {
						try {
							MulticastDnsRequestor r = new MulticastDnsRequestor(context);
							String answer = r.query(host);
							cb.success(answer); // Thread-safe.
						} catch (Exception ex) {
							cb.error(ex.getMessage());
						}
					}
				});
				return true;
			} else {
				callbackContext.error("unknown action: " + action);
				return false;
			}
		} catch (Exception ex) {
			callbackContext.error("Failed to execute plugin: " + ex.getMessage());
			return false;
		}
	}
}