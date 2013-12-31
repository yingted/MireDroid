package localhost.miredroid;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Observable;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.IBinder;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

public class BootService extends Service {
	private static final class VolatileObservable extends Observable {
		public synchronized boolean hasChanged() {
			return true;
		}
	}

	static final String[] RELAYS = new String[] {// @formatter:off
		"teredo.ipv6.microsoft.com",
		"teredo.ginzado.ne.jp",
		"teredo.iks-jena.de",
		"teredo.remlab.net",
		"teredo2.remlab.net",
	};// @formatter:on
	static final String DEFAULT_RELAY = RELAYS[3];
	static Observable started = new VolatileObservable();
	static final Object update = new Object();
	private static volatile boolean mStarted, mUninstall, mDenied;

	static final int SPAN_STDIN = Color.BLUE, SPAN_STDOUT = Color.BLACK, SPAN_STDERR = 0xff007f00, SPAN_PROMPT = Color.RED; // spannable
	static Observable log = new VolatileObservable();
	private static final SpannableStringBuilder mLog = new SpannableStringBuilder();
	static final int STATE_UNKNOWN = 0, STATE_INSTALLED = 1, STATE_UNINSTALLED = 2;

	private volatile Process mSu;
	private Writer mStdin;
	private volatile String mPrompt;
	private Thread mLogThread;
	private volatile Thread mExitThread;

	protected SharedPreferences mPrefs;

	/**
	 * @return whether the service is running
	 */
	public static boolean isStarted() {
		synchronized (started) {
			return mStarted;
		}
	}

	public static void setUninstall() {
		synchronized (started) {
			mUninstall = true;
		}
	}

	public static boolean wasDenied() {
		synchronized (started) {
			return mDenied;
		}
	}

	/**
	 * @return the log, which auto-resets
	 */
	public static CharSequence getLog() {
		synchronized (log) {
			return mLog;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		mPrefs = getSharedPreferences("miredo", 0);
		mDenied = false;
		mSu = null;
		mStdin = null;
		mExitThread = null;
		mPrompt = null;
		synchronized (log) {
			mLog.clear();
			log.notifyObservers();
		}
		(mLogThread = new Thread() {
			Collection<Closeable> mCloseables = new ArrayList<Closeable>();

			@Override
			public void interrupt() {
				d("interrupt!", new Exception());
				super.interrupt();
				synchronized (mCloseables) {
					for (Closeable c : mCloseables)
						try {
							c.close();
						} catch (IOException e) {
						}
					mCloseables.clear();
				}
			}

			private void open(Closeable c) {
				synchronized (mCloseables) {
					mCloseables.add(c);
				}
			}

			private void close(Closeable c) throws IOException {
				try {
					c.close();
				} finally {
					synchronized (mCloseables) {
						mCloseables.remove(c);
					}
				}
			}

			@Override
			public void run() {
				// d("=============================================== run");
				final File filesDir = getFilesDir();
				if (!mUninstall && !new File(filesDir, "mkshrc").exists() || true) { // XXX
					// debug
					// d("start unpack");
					final ZipInputStream zis = getZipInputStream();
					open(zis);
					try {
						try {
							final byte[] buffer = new byte[1024];
							for (ZipEntry ze; (ze = zis.getNextEntry()) != null;) {
								// d("unpacking " + ze.getName());
								final FileOutputStream fos = new FileOutputStream(new File(filesDir, ze.getName()));
								open(fos);
								try {
									for (int count; (count = zis.read(buffer)) != -1;)
										fos.write(buffer, 0, count);
								} finally {
									close(fos);
								}
							}
						} finally {
							close(zis);
						}
					} catch (IOException e) {
						stopSelf();
						d("install failed", e);
						if (!Thread.interrupted())
							e.printStackTrace();
						return;
					}
					// d("end unpack");
				}

				d("===============================================");
				try {
					{
						final Map<String, String> env = System.getenv();
						final String[] envArray = new String[env.size() + 1];
						int i = 0;
						for (Map.Entry<String, String> e : env.entrySet())
							envArray[i++] = e.getKey() + "=" + e.getValue();
						envArray[i++] = "FILES_DIR=" + filesDir.getAbsolutePath();
						synchronized (BootService.this) {
							mSu = Runtime.getRuntime().exec("su", envArray, new File("/system"));
						}
					}
					synchronized (update) {
						open(mStdin = new OutputStreamWriter(mSu.getOutputStream())); // UTF-8
					}
					final InputStream cout, cerr;
					open(cout = mSu.getInputStream());
					open(cerr = mSu.getErrorStream());
					synchronized (BootService.this) {
						(mExitThread = new Thread() {
							public void run() {
								d("su waitFor start");
								try {
									if (mSu.waitFor() == 0)
										return;
								} catch (InterruptedException e) {
									d("prompt received");
								} finally {
									d("su waitFor end");
								}
								if (mPrompt != null)
									return;
								d("=====denied=====");
								synchronized (started) {
									mDenied = true;
								}
								stopSelf(); // denied
							};
						}).start();
					}
					// update stderr first
					final InputStream[] readers = new InputStream[] { cerr, cout };
					final byte[][] buffers = new byte[2][4096];
					final int[] offsets = new int[2];
					final StringBuilder stdout = new StringBuilder(), stderr = new StringBuilder();
					for (boolean prevDirty = true;;) {
						boolean dirty = false;
						for (int i = 0; i < 2; ++i) {
							final InputStream br = readers[i];
							if (br == null) {
								d("skipping closed " + (i == 0 ? "stderr" : "stdout"));
								continue;
							}
							int avail, read = 0;
							d("start nonblocking");
							avail = br.available();
							while (offsets[i] + avail >= buffers[i].length)
								buffers[i] = Arrays.copyOf(buffers[i], buffers[i].length * 2);
							if (avail > 0)
								read = br.read(buffers[i], offsets[i], avail);
							d("done nonblocking: {" + new String(buffers[i], 0, offsets[i]) + "}");
							if (read < 0) {
								readers[i] = null;
								d("no more to read in " + (i == 0 ? "stderr" : "stdout"));
								continue;
							}
							if (read > 0)
								offsets[i] += read;
							else if (!dirty && !prevDirty) { // read == 0
								d("start blocking on " + (i == 0 ? "stderr" : "stdout"));
								final int ch = br.read(); // blocking
								d("stop blocking");
								if (ch < 0) {
									d("no more to read");
									readers[i] = null;
									continue; // process terminated; drain
								}
								buffers[i][offsets[i]++] = (byte) ch;
								if (offsets[i] == buffers[i].length)
									buffers[i] = Arrays.copyOf(buffers[i], buffers[i].length * 2);
								prevDirty = true;
							} else
								continue;
							dirty = true;
						}
						// d("========dirty======== " + dirty);
						if (prevDirty = dirty)
							continue;
						// all clean; need to update
						stderr.append(new String(buffers[0], 0, offsets[0]));
						stdout.append(new String(buffers[1], 0, offsets[1]));
						offsets[0] = offsets[1] = 0;
						synchronized (update) {
							update(stdout, stderr);
						}
						if (readers[0] == null && readers[1] == null)
							return; // we're done
					}
				} catch (IOException e) {
					d("logging stopped", e);
					if (!Thread.interrupted())
						e.printStackTrace();
				} finally {
					interrupt(); // close fds
					stopSelf();
				}
			}
		}).start();
		d("super.onCreate");
		super.onCreate();
	}

	protected int state;

	private String lastTyped = "";

	/**
	 * Types a string to su. May cause deadlock if called from within
	 * {@code mLogThread} with a long string.
	 * 
	 * @param cmd
	 *            the string to send
	 * @throws IOException
	 */
	protected void type(String cmd) throws IOException {
		if (mPrompt != null)
			lastTyped += cmd;
		mStdin.write(cmd);
		mStdin.flush();
	}

	private static final int STATE_MIREDO = 3;

	protected void update(StringBuilder stdout, StringBuilder stderr) throws IOException {
		d("stdout={" + stdout + "}, stderr={" + stderr + "}, state=" + state);
		assert lastTyped.length() == 0;
		final boolean prompted = mPrompt != null && stderr.length() >= mPrompt.length()
				&& mPrompt.contentEquals(stderr.subSequence(stderr.length() - mPrompt.length(), stderr.length()));
		if (prompted)
			stderr.setLength(stderr.length() - mPrompt.length());
		// @formatter:off
		switch (state) {
		case   0:
			d("load");
			type(". \"$FILES_DIR\"/mkshrc\n");
	++state;break;
		case   1: // only state to touch stderr
			if (stderr.length() == 0)
				return;
			synchronized (started) {
				assert mPrompt == null;
				assert !mStarted;
				mPrompt = stderr.toString();
				mStarted = true;
				//d("notifyObservers 2: "+started.countObservers());
				started.notifyObservers();
			}
			stderr.setLength(0);
			appendPrompt();
			if (mUninstall) {
				d("uninstall");
				mPrefs.edit().putInt("state", STATE_UNKNOWN);
				type("uninstall\n");
				state = 100;
				break;
			}
			d("install");
			final String cmd = "install '" + mPrefs.getString("relay", DEFAULT_RELAY).replace("'", "'\\''") + "'" + (mPrefs.getInt("state", STATE_UNKNOWN) == STATE_INSTALLED ? " --cached" : "") + "\n";
			mPrefs.edit().putInt("state", STATE_UNKNOWN).commit();
			type (cmd);
	++state;break;
		case   2:
			if (!prompted)
				return;
			if ("success\n".contentEquals(stdout))
				mPrefs.edit().putInt("state", STATE_INSTALLED).commit();
			type("miredo\n"); // never returns
	++state;break;
		case   3: // STATE_MIREDO
			append(stdout, SPAN_STDOUT);
			if (!mStarted) {
				type("\n");
				lastTyped = ""; // hide this
			}
			break;

		case 100:
			if (!prompted)
				return;
			if ("success\n".contentEquals(stdout)) {
				mPrefs.edit().putInt("state", STATE_UNINSTALLED).commit();
				final File filesDir = getFilesDir();
				new File(filesDir, "mkshrc").delete();
				for (final String name : filesDir.list())
					new File(filesDir, name).delete();
				stopSelf();
			}
	++state;break;
		}
		// @formatter:on
		append(stderr, SPAN_STDERR);
		if (prompted) { // float stdout to end
			append(stdout, SPAN_STDOUT);
			appendPrompt();
		}
		d("emptied stdout, stderr: " + stdout.length() + ", " + stderr.length());
		append(lastTyped, SPAN_STDIN);
		lastTyped = "";
	}

	protected void append(CharSequence s, int color) {
		final int len = s.length();
		if (len == 0)
			return;
		synchronized (log) {
			final int prev = mLog.length();
			mLog.append(s);
			if (color != Color.BLACK)
				mLog.setSpan(new ForegroundColorSpan(color), prev, prev + len, 0);
			log.notifyObservers();
		}
		if (s instanceof StringBuilder) // java lacks specialization
			((StringBuilder) s).setLength(0);
	}

	protected void appendPrompt() {
		append("# ", SPAN_PROMPT);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		synchronized (started) {
			started.notifyObservers(); // to be sure
		}
		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		d("onDestroy", new Exception());
		synchronized (update) { // block out commands
			synchronized (started) {
				mStarted = false;
				mUninstall = false;
				// d("notifyObservers: " + started.countObservers());
				started.notifyObservers();
				d("send exit");
				if (state == STATE_MIREDO)
					try {
						type("\n");
						lastTyped = "";
					} catch (IOException e) {
						e.printStackTrace();
					}
				d("interrupt");
				mLogThread.interrupt();
				d("interrupt 2");
				synchronized (this) {
					if (mExitThread != null)
						mExitThread.interrupt();
				}
				d("destroy");
				synchronized (this) {
					if (mSu != null)
						mSu.destroy();
				}
			}
		}
		for (;;)
			try {
				d("waitFor");
				mSu.waitFor();
				d("join");
				mLogThread.join();
				d("join 2");
				mExitThread.join();
				break;
			} catch (InterruptedException e) {
				d("interrupt");
			}
		mDenied = false;
		d("super.onDestroy");
		super.onDestroy();
	}

	private final ZipInputStream getZipInputStream() {
		return new ZipInputStream(getResources().openRawResource(R.raw.miredo));
	}

	public static void d(final String msg) {
		Log.v("MireDroid", msg);
	}

	public static void d(final String msg, Throwable t) {
		if (false)
			Log.v("MireDroid", msg, t);
		else
			d(msg);
	}
}
