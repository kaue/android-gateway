package org.envaya.kalsms;

import org.envaya.kalsms.task.PollerTask;
import org.envaya.kalsms.task.HttpTask;
import org.envaya.kalsms.receiver.OutgoingMessagePoller;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.telephony.SmsManager;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.util.Log;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import org.apache.http.message.BasicNameValuePair;

public final class App extends Application {
    
    public static final String ACTION_OUTGOING = "outgoing";
    public static final String ACTION_INCOMING = "incoming";
    public static final String ACTION_SEND_STATUS = "send_status";
    
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_SENT = "sent";
    
    public static final String MESSAGE_TYPE_MMS = "mms";
    public static final String MESSAGE_TYPE_SMS = "sms";
    
    public static final String LOG_NAME = "KALSMS";
    public static final String LOG_INTENT = "org.envaya.kalsms.LOG";
    
    public static final int MAX_DISPLAYED_LOG = 15000;
    public static final int LOG_TIMESTAMP_INTERVAL = 60000;
    
    // Each QueuedMessage is identified within our internal Map by its Uri.
    // Currently QueuedMessage instances are only available within KalSMS,
    // (but they could be made available to other applications later via a ContentProvider)
    public static final Uri CONTENT_URI = Uri.parse("content://org.envaya.kalsms");
    public static final Uri INCOMING_URI = Uri.withAppendedPath(CONTENT_URI, "incoming");
    public static final Uri OUTGOING_URI = Uri.withAppendedPath(CONTENT_URI, "outgoing");
        
    private Map<Uri, IncomingMessage> incomingMessages = new HashMap<Uri, IncomingMessage>();
    private Map<Uri, OutgoingMessage> outgoingMessages = new HashMap<Uri, OutgoingMessage>();    
    
    private SharedPreferences settings;
    private MmsObserver mmsObserver;
    private SpannableStringBuilder displayedLog = new SpannableStringBuilder();
    private long lastLogTime;
    
    private MmsUtils mmsUtils;
    
    @Override
    public void onCreate()
    {
        super.onCreate();
        
        settings = PreferenceManager.getDefaultSharedPreferences(this);        
        mmsUtils = new MmsUtils(this);
        
        log(Html.fromHtml(
            isEnabled() ? "<b>SMS gateway running.</b>" : "<b>SMS gateway disabled.</b>"));

        log("Server URL is: " + getDisplayString(getServerUrl()));
        log("Your phone number is: " + getDisplayString(getPhoneNumber()));        
        
        mmsObserver = new MmsObserver(this);
        mmsObserver.register();
        
        setOutgoingMessageAlarm();
    }    

    public void checkOutgoingMessages() 
    {
        String serverUrl = getServerUrl();
        if (serverUrl.length() > 0) {
            log("Checking for outgoing messages");
            new PollerTask(this).execute();
        } else {
            log("Can't check outgoing messages; server URL not set");
        }
    }

    public void setOutgoingMessageAlarm() {
        AlarmManager alarm = (AlarmManager) getSystemService(Context.ALARM_SERVICE);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(this,
                0,
                new Intent(this, OutgoingMessagePoller.class),
                0);

        alarm.cancel(pendingIntent);

        int pollSeconds = getOutgoingPollSeconds();

        if (isEnabled())
        {        
            if (pollSeconds > 0) {
                alarm.setRepeating(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime(),
                        pollSeconds * 1000,
                        pendingIntent);
                log("Checking for outgoing messages every " + pollSeconds + " sec");
            } else {
                log("Not checking for outgoing messages.");
            }
        }
    }

    public String getDisplayString(String str) {
        if (str.length() == 0) {
            return "(not set)";
        } else {
            return str;
        }
    }

    public String getServerUrl() {
        return settings.getString("server_url", "");
    }

    public String getPhoneNumber() {
        return settings.getString("phone_number", "");
    }

    public int getOutgoingPollSeconds() {
        return Integer.parseInt(settings.getString("outgoing_interval", "0"));
    }

    public boolean isEnabled()
    {
        return settings.getBoolean("enabled", false);
    }
    
    public boolean getKeepInInbox() 
    {
        return settings.getBoolean("keep_in_inbox", false);        
    }

    public String getPassword() {
        return settings.getString("password", "");
    }

    private void notifyStatus(OutgoingMessage sms, String status, String errorMessage) {
        String serverId = sms.getServerId();

        String logMessage;
        if (status.equals(App.STATUS_SENT)) {
            logMessage = "sent successfully";
        } else if (status.equals(App.STATUS_FAILED)) {
            logMessage = "could not be sent (" + errorMessage + ")";
        } else {
            logMessage = "queued";
        }
        String smsDesc = sms.getLogName();

        if (serverId != null) {
            log("Notifying server " + smsDesc + " " + logMessage);

            new HttpTask(this,
                new BasicNameValuePair("id", serverId),
                new BasicNameValuePair("status", status),
                new BasicNameValuePair("error", errorMessage),
                new BasicNameValuePair("action", App.ACTION_SEND_STATUS)                    
            ).execute();
        } else {
            log(smsDesc + " " + logMessage);
        }
    }

    public synchronized void retryStuckMessages() {
        retryStuckOutgoingMessages();
        retryStuckIncomingMessages();
    }

    public synchronized int getStuckMessageCount() {
        return outgoingMessages.size() + incomingMessages.size();
    }

    public synchronized void retryStuckOutgoingMessages() {
        for (OutgoingMessage sms : outgoingMessages.values()) {
            sms.retryNow();
        }
    }

    public synchronized void retryStuckIncomingMessages() {
        for (IncomingMessage sms : incomingMessages.values()) {
            sms.retryNow();
        }
    }
    
    public synchronized void setIncomingMessageStatus(IncomingMessage message, boolean success) {        
        Uri uri = message.getUri();
        if (success)
        {
            incomingMessages.remove(uri);
            
            if (message instanceof IncomingMms)
            {
                IncomingMms mms = (IncomingMms)message;
                if (!getKeepInInbox())
                {
                    log("Deleting MMS " + mms.getId() + " from inbox...");
                    mmsUtils.deleteFromInbox(mms);
                }            
            }
        }
        else if (!message.scheduleRetry())
        {
            incomingMessages.remove(uri);
        }
    }    

    public synchronized void notifyOutgoingMessageStatus(Uri uri, int resultCode) {
        OutgoingMessage sms = outgoingMessages.get(uri);

        if (sms == null) {
            return;
        }

        switch (resultCode) {
            case Activity.RESULT_OK:
                this.notifyStatus(sms, App.STATUS_SENT, "");
                break;
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                this.notifyStatus(sms, App.STATUS_FAILED, "generic failure");
                break;
            case SmsManager.RESULT_ERROR_RADIO_OFF:
                this.notifyStatus(sms, App.STATUS_FAILED, "radio off");
                break;
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                this.notifyStatus(sms, App.STATUS_FAILED, "no service");
                break;
            case SmsManager.RESULT_ERROR_NULL_PDU:
                this.notifyStatus(sms, App.STATUS_FAILED, "null PDU");
                break;
            default:
                this.notifyStatus(sms, App.STATUS_FAILED, "unknown error");
                break;
        }

        switch (resultCode) {
            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
            case SmsManager.RESULT_ERROR_RADIO_OFF:
            case SmsManager.RESULT_ERROR_NO_SERVICE:
                if (!sms.scheduleRetry()) {
                    outgoingMessages.remove(uri);
                }
                break;
            default:
                outgoingMessages.remove(uri);
                break;
        }
    }

    public synchronized void sendOutgoingMessage(OutgoingMessage sms) {
        Uri uri = sms.getUri();
        if (outgoingMessages.containsKey(uri)) {
            log(sms.getLogName() + " already sent, skipping");
            return;
        }

        outgoingMessages.put(uri, sms);

        log("Sending " + sms.getLogName() + " to " + sms.getTo());
        sms.trySend();
    }

    public synchronized void forwardToServer(IncomingMessage message) {
        Uri uri = message.getUri();
        
        if (incomingMessages.containsKey(uri)) {
            log("Duplicate incoming "+message.getDisplayType()+", skipping");
            return;
        }

        incomingMessages.put(uri, message);

        log("Received "+message.getDisplayType()+" from " + message.getFrom());

        message.tryForwardToServer();
    }

    public synchronized void retryIncomingMessage(Uri uri) {
        IncomingMessage message = incomingMessages.get(uri);
        if (message != null) {
            message.retryNow();
        }
    }

    public synchronized void retryOutgoingMessage(Uri uri) {
        OutgoingMessage sms = outgoingMessages.get(uri);
        if (sms != null) {
            sms.retryNow();
        }
    }

    public void debug(String msg) {
        Log.d(LOG_NAME, msg);
    }

    public synchronized void log(CharSequence msg) 
    {
        Log.d(LOG_NAME, msg.toString());       
                                 
        // prevent displayed log from growing too big
        int length = displayedLog.length();
        if (length > MAX_DISPLAYED_LOG)
        {
            int startPos = length - MAX_DISPLAYED_LOG * 3 / 4;
            
            for (int cur = startPos; cur < startPos + 100 && cur < length; cur++)
            {
                if (displayedLog.charAt(cur) == '\n')
                {
                    startPos = cur;
                    break;
                }
            }

            displayedLog.replace(0, startPos, "[Older log messages not shown]\n");
        }

        // display a timestamp in the log occasionally        
        long logTime = SystemClock.elapsedRealtime();        
        if (logTime - lastLogTime > LOG_TIMESTAMP_INTERVAL)
        {
            Date date = new Date();
            displayedLog.append("[" + DateFormat.getTimeInstance().format(date) + "]\n");                
            lastLogTime = logTime;
        }
        
        displayedLog.append(msg);
        displayedLog.append("\n");        
            
        Intent broadcast = new Intent(App.LOG_INTENT);
        sendBroadcast(broadcast);
    }
    
    public synchronized CharSequence getDisplayedLog()
    {
        return displayedLog;
    }
    
    public void logError(Throwable ex) {
        logError("ERROR", ex);
    }

    public void logError(String msg, Throwable ex) {
        logError(msg, ex, false);
    }

    public void logError(String msg, Throwable ex, boolean detail) {
        log(msg + ": " + ex.getClass().getName() + ": " + ex.getMessage());

        if (detail) {
            for (StackTraceElement elem : ex.getStackTrace()) {
                log(elem.getClassName() + ":" + elem.getMethodName() + ":" + elem.getLineNumber());
            }
            Throwable innerEx = ex.getCause();
            if (innerEx != null) {
                logError("Inner exception:", innerEx, true);
            }
        }
    }    
    
    public MmsUtils getMmsUtils()
    {
        return mmsUtils;
    }
}