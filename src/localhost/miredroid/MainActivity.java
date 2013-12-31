package localhost.miredroid;

import java.util.Observable;
import java.util.Observer;

import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.TextView;
import android.widget.ToggleButton;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

public class MainActivity extends Activity implements OnCheckedChangeListener, Observer, OnClickListener, Runnable {
	protected ToggleButton mDaemonToggle, mServiceToggle;
	protected Button mUninstall;
	protected AutoCompleteTextView mRelayInput;
	protected TextView mLogText;
	private SharedPreferences mPrefs;
	protected ComponentName mBootReceiver;

	protected class Other extends ArrayAdapter<String> implements OnCheckedChangeListener, OnClickListener, Observer, Runnable {
		public Other(Context context, int resource, String[] objects) {
			super(context, resource, objects);
		}

		@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
			if (!isClickable)
				return;
			getPackageManager().setComponentEnabledSetting(mBootReceiver,
					isChecked ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
					PackageManager.DONT_KILL_APP);
		}

		@Override
		public void onClick(View v) {
			mRelayInput.showDropDown();
		}

		@Override
		public void update(Observable observable, Object data) {
			if (isClickable)
				runOnUiThread(this);
		}

		@Override
		public void run() {
			mLogText.setText(BootService.getLog());
		}
	}

	protected Other mOther;

	protected boolean isClickable;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		mOther = new Other(this, android.R.layout.simple_list_item_1, BootService.RELAYS);
		mBootReceiver = new ComponentName(MainActivity.this, BootReceiver.class);

		mDaemonToggle = (ToggleButton) findViewById(R.id.daemonToggle);
		mServiceToggle = (ToggleButton) findViewById(R.id.serviceToggle);
		mUninstall = (Button) findViewById(R.id.uninstallButton);
		mRelayInput = (AutoCompleteTextView) findViewById(R.id.relayInput);
		mLogText = (TextView) findViewById(R.id.logText);

		mPrefs = getSharedPreferences("miredo", 0);

		mDaemonToggle.setOnCheckedChangeListener(this);
		mServiceToggle.setOnCheckedChangeListener(mOther);
		mUninstall.setOnClickListener(this);
		mRelayInput.setAdapter(mOther);
		mRelayInput.setText(BootService.DEFAULT_RELAY);
		mRelayInput.setOnClickListener(mOther);

		BootService.started.addObserver(this);
		BootService.log.addObserver(mOther);
		update(null, null);
		BootService.d("**********************************************");
	}

	protected void onResume() {
		mServiceToggle
				.setChecked(getPackageManager().getComponentEnabledSetting(mBootReceiver) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
		isClickable = true;
		run();
		mOther.run();
		super.onResume();
	}

	@Override
	protected void onPause() {
		isClickable = false;
		super.onPause();
	}

	protected static final int STATE_DISABLED = 0, STATE_ENABLING = 1, STATE_ENABLED = 2, STATE_DISABLING = 3, STATE_UNINSTALLING = 4;
	protected volatile int state = STATE_DISABLED; // XXX race condition on
													// start

	@Override
	public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
		if (!isClickable)
			return;
		if (isChecked) {
			mPrefs.edit().putString("relay", mRelayInput.getText().toString()).commit();
			state = STATE_ENABLING;
			startService();
		} else {
			state = STATE_DISABLING;
			stopService(new Intent(this, BootService.class));
		}
		isClickable = false;
		buttonView.setChecked(state == STATE_DISABLED);
		isClickable = true;
	}

	private final void startService() {
		mDaemonToggle.setEnabled(false);
		mRelayInput.setEnabled(false);
		mUninstall.setEnabled(false);
		startService(new Intent(this, BootService.class));
	}

	@Override
	public void update(Observable observable, Object data) {
		// BootService.d("start state=" + state + " start=" +
		// BootService.isStarted() + " deny=" + BootService.wasDenied());
		if (BootService.wasDenied())
			state = BootService.isStarted() ? STATE_DISABLING : STATE_DISABLED;
		else if (BootService.isStarted()) {
			if (state == STATE_UNINSTALLING)
				state = STATE_DISABLING;
			else if (state != STATE_DISABLING)
				state = STATE_ENABLED;
		} else if (state != STATE_ENABLING && (state != STATE_UNINSTALLING))
			state = STATE_DISABLED;
		BootService.d("state=" + state);
		if (isClickable)
			runOnUiThread(this);
	}

	@Override
	public void onClick(View v) {
		mPrefs.edit().clear().apply();
		BootService.setUninstall();
		state = STATE_UNINSTALLING;
		startService(); // who cares about race
	}

	@Override
	public void run() {
		if (!isClickable)
			return;
		final boolean idle = state == STATE_DISABLED;
		isClickable = false;
		mDaemonToggle.setChecked(!idle);
		isClickable = true;
		mDaemonToggle.setEnabled(state == STATE_ENABLED || state == STATE_DISABLED);
		mUninstall.setEnabled(idle && mPrefs.getInt("state", BootService.STATE_UNKNOWN) != BootService.STATE_UNINSTALLED);
		mRelayInput.setEnabled(idle);
	}
}
