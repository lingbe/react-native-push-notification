package com.dieam.reactnativepushnotification.modules;


import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.core.app.NotificationCompat;
import android.util.Log;

import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase;
import android.database.Cursor;

import com.facebook.react.bridge.ReadableMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.text.DateFormat;
import java.text.SimpleDateFormat;


import com.dieam.reactnativepushnotification.helpers.ApplicationBadgeHelper;
import static com.dieam.reactnativepushnotification.modules.RNPushNotification.LOG_TAG;
import static com.dieam.reactnativepushnotification.modules.RNPushNotificationAttributes.fromJson;

public class RNPushNotificationHelper {
    public static final String PREFERENCES_KEY = "rn_push_notification";
    private static final long DEFAULT_VIBRATION = 300L;
    private static final String NOTIFICATION_CHANNEL_ID = "rn-push-notification-channel-id";
    private static HashMap<Integer, ArrayList<String>> messageMap = new HashMap<Integer, ArrayList<String>>();
    private static int messageCountAll = 0;

    private Context context;
    private RNPushNotificationConfig config;
    private final SharedPreferences scheduledNotificationsPersistence;
    private static final int ONE_MINUTE = 60 * 1000;
    private static final long ONE_HOUR = 60 * ONE_MINUTE;
    private static final long ONE_DAY = 24 * ONE_HOUR;

    public RNPushNotificationHelper(Application context) {
        this.context = context;
        this.config = new RNPushNotificationConfig(context);
        this.scheduledNotificationsPersistence = context.getSharedPreferences(RNPushNotificationHelper.PREFERENCES_KEY, Context.MODE_PRIVATE);
    }

    public Class getMainActivityClass() {
        String packageName = context.getPackageName();
        Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        String className = launchIntent.getComponent().getClassName();
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void setNotificationHistory(int notId, String message) {
        ArrayList<String> messageList = messageMap.get(notId);
        if (messageList == null) {
            messageList = new ArrayList<String>();
            messageMap.put(notId, messageList);
        }

        if (message.isEmpty()) {
            messageList.clear();
        } else {
            messageList.add(message);
        }
    }

    public void clearNotificationHistory() {
        messageMap.clear();
        messageCountAll = 0;
        ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(context, messageCountAll);
    }

    private AlarmManager getAlarmManager() {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    private PendingIntent toScheduleNotificationIntent(Bundle bundle) {
        int notificationID = Integer.parseInt(bundle.getString("id"));

        Intent notificationIntent = new Intent(context, RNPushNotificationPublisher.class);
        notificationIntent.putExtra(RNPushNotificationPublisher.NOTIFICATION_ID, notificationID);
        notificationIntent.putExtras(bundle);

        return PendingIntent.getBroadcast(context, notificationID, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public void sendNotificationScheduled(Bundle bundle) {
        Class intentClass = getMainActivityClass();
        if (intentClass == null) {
            Log.e(LOG_TAG, "No activity class found for the scheduled notification");
            return;
        }

        if (bundle.getString("message") == null) {
            Log.e(LOG_TAG, "No message specified for the scheduled notification");
            return;
        }

        if (bundle.getString("id") == null) {
            Log.e(LOG_TAG, "No notification ID specified for the scheduled notification");
            return;
        }

        double fireDate = bundle.getDouble("fireDate");
        if (fireDate == 0) {
            Log.e(LOG_TAG, "No date specified for the scheduled notification");
            return;
        }

        RNPushNotificationAttributes notificationAttributes = new RNPushNotificationAttributes(bundle);
        String id = notificationAttributes.getId();

        Log.d(LOG_TAG, "Storing push notification with id " + id);

        SharedPreferences.Editor editor = scheduledNotificationsPersistence.edit();
        editor.putString(id, notificationAttributes.toJson().toString());
        commit(editor);

        boolean isSaved = scheduledNotificationsPersistence.contains(id);
        if (!isSaved) {
            Log.e(LOG_TAG, "Failed to save " + id);
        }

        sendNotificationScheduledCore(bundle);
    }

    public void sendNotificationScheduledCore(Bundle bundle) {
        long fireDate = (long) bundle.getDouble("fireDate");

        // If the fireDate is in past, this will fire immediately and show the
        // notification to the user
        PendingIntent pendingIntent = toScheduleNotificationIntent(bundle);

        Log.d(LOG_TAG, String.format("Setting a notification with id %s at time %s",
                bundle.getString("id"), Long.toString(fireDate)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getAlarmManager().setExact(AlarmManager.RTC_WAKEUP, fireDate, pendingIntent);
        } else {
            getAlarmManager().set(AlarmManager.RTC_WAKEUP, fireDate, pendingIntent);
        }
    }

    public void sendToNotificationCentre(Bundle bundle) {
        try {
            Class intentClass = getMainActivityClass();
            if (intentClass == null) {
                Log.e(LOG_TAG, "No activity class found for the notification");
                return;
            }

            if (bundle.getString("message") == null) {
                // this happens when a 'data' notification is received - we do not synthesize a local notification in this case
                Log.d(LOG_TAG, "Cannot send to notification centre because there is no 'message' field in: " + bundle);
                return;
            }

            String notificationIdString = bundle.getString("id");
            if (notificationIdString == null) {
                Log.e(LOG_TAG, "No notification ID specified for the notification");
                return;
            }

            Resources res = context.getResources();
            String packageName = context.getPackageName();

            String title = bundle.getString("title");
            if (title == null) {
                ApplicationInfo appInfo = context.getApplicationInfo();
                title = context.getPackageManager().getApplicationLabel(appInfo).toString();
            }

            int priority = NotificationCompat.PRIORITY_HIGH;
            final String priorityString = bundle.getString("priority");

            if (priorityString != null) {
                switch(priorityString.toLowerCase()) {
                    case "max":
                        priority = NotificationCompat.PRIORITY_MAX;
                        break;
                    case "high":
                        priority = NotificationCompat.PRIORITY_HIGH;
                        break;
                    case "low":
                        priority = NotificationCompat.PRIORITY_LOW;
                        break;
                    case "min":
                        priority = NotificationCompat.PRIORITY_MIN;
                        break;
                    case "default":
                        priority = NotificationCompat.PRIORITY_DEFAULT;
                        break;
                    default:
                        priority = NotificationCompat.PRIORITY_HIGH;
                }
            }

            int visibility = NotificationCompat.VISIBILITY_PRIVATE;
            final String visibilityString = bundle.getString("visibility");

            if (visibilityString != null) {
                switch(visibilityString.toLowerCase()) {
                    case "private":
                        visibility = NotificationCompat.VISIBILITY_PRIVATE;
                        break;
                    case "public":
                        visibility = NotificationCompat.VISIBILITY_PUBLIC;
                        break;
                    case "secret":
                        visibility = NotificationCompat.VISIBILITY_SECRET;
                        break;
                    default:
                        visibility = NotificationCompat.VISIBILITY_PRIVATE;
                }
            }

            NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle(title)
                    .setTicker(bundle.getString("ticker"))
                    .setVisibility(visibility)
                    .setPriority(priority)
                    .setAutoCancel(bundle.getBoolean("autoCancel", true));

            String group = bundle.getString("group");
            if (group != null) {
                notification.setGroup(group);
            }

            notification.setContentText(bundle.getString("message"));

            String largeIcon = bundle.getString("largeIcon");

            String subText = bundle.getString("subText");

            if (subText != null) {
                notification.setSubText(subText);
            }

            String numberString = bundle.getString("number");
            if (numberString != null) {
                notification.setNumber(Integer.parseInt(numberString));
            }

            int smallIconResId;
            int largeIconResId;

            String smallIcon = bundle.getString("smallIcon");

            if (smallIcon != null) {
                smallIconResId = res.getIdentifier(smallIcon, "mipmap", packageName);
            } else {
                smallIconResId = res.getIdentifier("ic_notification", "mipmap", packageName);
            }

            if (smallIconResId == 0) {
                smallIconResId = res.getIdentifier("ic_launcher", "mipmap", packageName);

                if (smallIconResId == 0) {
                    smallIconResId = android.R.drawable.ic_dialog_info;
                }
            }

            if (largeIcon != null) {
                largeIconResId = res.getIdentifier(largeIcon, "mipmap", packageName);
            } else {
                largeIconResId = res.getIdentifier("ic_launcher", "mipmap", packageName);
            }

            Bitmap largeIconBitmap = BitmapFactory.decodeResource(res, largeIconResId);

            if (largeIconResId != 0 && (largeIcon != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)) {
                notification.setLargeIcon(largeIconBitmap);
            }

            notification.setSmallIcon(smallIconResId);
            String bigText = bundle.getString("bigText");

            if (bigText == null) {
                bigText = bundle.getString("message");
            }

            notification.setStyle(new NotificationCompat.BigTextStyle().bigText(bigText));

            // TIPS: Section of "ibox style notification"
            // - Notificaiton inbox are grouped by id so this way is posible to have multiple inbox
            // - If app is foreground nothing to do here there are inside "sendToNotificationCentre"
            if(bundle.containsKey("style") && bundle.getString("style").equals("inbox")){
                int notId = Integer.parseInt(bundle.getString("id"));
                setNotificationHistory(notId, bundle.getString("message"));
                ArrayList<String> messageList = messageMap.get(notId);
                Integer sizeList = messageList.size();

                if (sizeList > 1) {
                    String sizeListMessage = sizeList.toString();
                    String stacking = sizeList + " more";
                    if (bundle.getString("summaryText") != null) {
                        stacking = bundle.getString("summaryText");
                        stacking = stacking.replace("%n%", sizeListMessage);
                    }
                    NotificationCompat.InboxStyle notificationInbox = new NotificationCompat.InboxStyle()
                        .setBigContentTitle(bundle.getString("title")).setSummaryText(stacking);

                    for (int i = messageList.size() - 1; i >= 0; i--) {
                        notificationInbox.addLine(messageList.get(i));
                    }

                    notification.setStyle(notificationInbox);
                }
                NotificationManager notificationManager = notificationManager();
                notificationManager.cancel(notId);
            }

            Intent intent = new Intent(context, intentClass);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            bundle.putBoolean("userInteraction", true);
            intent.putExtra("notification", bundle);
            // TIP: all notifications that go to "notificationCentre" pass through here but when user in
            // so clearNotificationHistory should go on "userInteraction" set to false but seems to be better handle on open app in js side

            if (!bundle.containsKey("playSound") || bundle.getBoolean("playSound")) {
                Uri soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                String soundName = bundle.getString("soundName");
                if (soundName != null) {
                    if (!"default".equalsIgnoreCase(soundName)) {

                        // sound name can be full filename, or just the resource name.
                        // So the strings 'my_sound.mp3' AND 'my_sound' are accepted
                        // The reason is to make the iOS and android javascript interfaces compatible

                        int resId;
                        if (context.getResources().getIdentifier(soundName, "raw", context.getPackageName()) != 0) {
                            resId = context.getResources().getIdentifier(soundName, "raw", context.getPackageName());
                        } else {
                            soundName = soundName.substring(0, soundName.lastIndexOf('.'));
                            resId = context.getResources().getIdentifier(soundName, "raw", context.getPackageName());
                        }

                        soundUri = Uri.parse("android.resource://" + context.getPackageName() + "/" + resId);
                    }
                }
                notification.setSound(soundUri);
            }

            if (bundle.containsKey("ongoing") || bundle.getBoolean("ongoing")) {
                notification.setOngoing(bundle.getBoolean("ongoing"));
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                notification.setCategory(NotificationCompat.CATEGORY_CALL);

                String color = bundle.getString("color");
                int defaultColor = this.config.getNotificationColor();
                if (color != null) {
                    notification.setColor(Color.parseColor(color));
                } else if (defaultColor != -1) {
                    notification.setColor(defaultColor);
                }
            }

            int notificationID = Integer.parseInt(notificationIdString);

            PendingIntent pendingIntent = PendingIntent.getActivity(context, notificationID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationManager notificationManager = notificationManager();
            checkOrCreateChannel(notificationManager);

            notification.setContentIntent(pendingIntent);

            if (!bundle.containsKey("vibrate") || bundle.getBoolean("vibrate")) {
                long vibration = bundle.containsKey("vibration") ? (long) bundle.getDouble("vibration") : DEFAULT_VIBRATION;
                if (vibration == 0)
                    vibration = DEFAULT_VIBRATION;
                notification.setVibrate(new long[]{0, vibration});
            }

            JSONArray actionsArray = null;
            try {
                actionsArray = bundle.getString("actions") != null ? new JSONArray(bundle.getString("actions")) : null;
            } catch (JSONException e) {
                Log.e(LOG_TAG, "Exception while converting actions to JSON object.", e);
            }

            if (actionsArray != null) {
                // No icon for now. The icon value of 0 shows no icon.
                int icon = 0;

                // Add button for each actions.
                for (int i = 0; i < actionsArray.length(); i++) {
                    String action;
                    try {
                        action = actionsArray.getString(i);
                    } catch (JSONException e) {
                        Log.e(LOG_TAG, "Exception while getting action from actionsArray.", e);
                        continue;
                    }

                    Intent actionIntent = new Intent(context, intentClass);
                    actionIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    actionIntent.setAction(context.getPackageName() + "." + action);

                    // Add "action" for later identifying which button gets pressed.
                    bundle.putString("action", action);
                    actionIntent.putExtra("notification", bundle);

                    PendingIntent pendingActionIntent = PendingIntent.getActivity(context, notificationID, actionIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
                    notification.addAction(icon, action, pendingActionIntent);
                }
            }

            // Remove the notification from the shared preferences once it has been shown
            // to avoid showing the notification again when the phone is rebooted. If the
            // notification is not removed, then every time the phone is rebooted, we will
            // try to reschedule all the notifications stored in shared preferences and since
            // these notifications will be in the past time, they will be shown immediately
            // to the user which we shouldn't do. So, remove the notification from the shared
            // preferences once it has been shown to the user. If it is a repeating notification
            // it will be scheduled again.
            if (scheduledNotificationsPersistence.getString(notificationIdString, null) != null) {
                SharedPreferences.Editor editor = scheduledNotificationsPersistence.edit();
                editor.remove(notificationIdString);
                commit(editor);
            }

            Notification info = notification.build();
            info.defaults |= Notification.DEFAULT_LIGHTS;

            // Posts a notification to be shown in the status bar
            // TIP: if some crash come after this will be capture and the notification will be displayed anyway
            if (bundle.containsKey("tag")) {
                String tag = bundle.getString("tag");
                notificationManager.notify(tag, notificationID, info);
            } else {
                notificationManager.notify(notificationID, info);
            }

            // Can't use setRepeating for recurring notifications because setRepeating
            // is inexact by default starting API 19 and the notifications are not fired
            // at the exact time. During testing, it was found that notifications could
            // late by many minutes.
            this.scheduleNextNotificationIfRepeating(bundle);

            // TIPS: Section of "Increment Badge"
            if(bundle.containsKey("incrementBadge") && bundle.getString("incrementBadge").equals("true")){
                messageCountAll ++;
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(context, messageCountAll);
            }else if(bundle.containsKey("badge")){
                ApplicationBadgeHelper.INSTANCE.setApplicationIconBadgeNumber(context, Integer.parseInt(bundle.getString("badge")));
                messageCountAll = Integer.parseInt(bundle.getString("badge"));
            }

            // TIP: update last_message in contacts (in background like whatsapp)
            // Connecting with SQLite parat

            // Log.i(LOG_TAG, "SQLiteDatabase bundle: " + bundle);

            // WatermelonDB says: On some systems there is some kind of lock on `/databases` folder so we get from parent folder
            String dbPath = context.getDatabasePath("watermelon.db").toString().replace("/databases", "");
            SQLiteDatabase db =  SQLiteDatabase.openOrCreateDatabase(dbPath, null);
            // Log.i(LOG_TAG, "SQLiteDatabase db: " + db);

            // Show all tables (rawQuery)
            // Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            // if (c.moveToFirst()) {
            //     while ( !c.isAfterLast() ) {
            //         // Toast.makeText(activityName.this, "Table Name=> "+c.getString(0), Toast.LENGTH_LONG).show();
            //         Log.i(LOG_TAG, "SQLiteDatabase cursor tables: " + c.getString(0));
            //         c.moveToNext();
            //     }
            // }

            // TIP: update last_message if has payload and type: "message" 
            String payloadType = "";
            if(bundle.containsKey("payload")){
                JSONObject payload = new JSONObject(bundle.getString("payload"));
                if(payload.has("type"))
                    payloadType = payload.getString("type");
            }

            if(payloadType.equals("message")){
                // TIP: for "last_message_created" in push bundle "google.sent_time" I think can be used or "System.currentTimeMillis()"
                Long tsLong = System.currentTimeMillis();
                String tsString = tsLong.toString()+".0";
                DateFormat dfMessage = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                String createdAtFormat = dfMessage.format(new Date(tsLong));
                if(bundle.containsKey("google.sent_time")){
                    tsLong = bundle.getLong("google.sent_time");
                    createdAtFormat = dfMessage.format(new Date(tsLong));
                    tsString = tsLong.toString()+".0";
                }

                String contactId = "";
                String rawText = "";
                String messageId = "";
                if(bundle.containsKey("payload")){
                    JSONObject payload = new JSONObject(bundle.getString("payload"));
                    JSONObject entity = payload.getJSONObject("entity");
                    contactId = entity.getString("contact");
                    if(entity.has("attributes")){
                        JSONObject attributes = entity.getJSONObject("attributes");
                        messageId = attributes.getString("messageId");
                        rawText = attributes.getString("rawText");
                        // Log.i(LOG_TAG, "SQLiteDatabase cursor messageId: " + messageId);
                        // Log.i(LOG_TAG, "SQLiteDatabase cursor rawText: " + rawText);
                    }
                }

                String lastMessage = "";
                if(bundle.containsKey("message")){
                    lastMessage = bundle.getString("message");
                }

                String msNotRead = "0";
                int notId = Integer.parseInt(bundle.getString("id"));
                ArrayList<String> messageList = messageMap.get(notId);
                msNotRead = Integer.toString(messageList.size());

                // TODO: createdAt should have this format: "2020-06-18T15:29:50.285Z" now have "2020-06-26T14:20:21"
                String lastMessageJson = "{\"_id\":\"fromPushPluginId\",\"audio\":null,\"correct\":null,\"createdAt\":\"${ca}\",\"image\":null,\"marker\":{\"type\":\"markable\"},\"text\":\"${lm}\",\"user\":{\"_id\":\"${_id}\"}}".replace("${lm}", lastMessage).replace("${_id}", contactId).replace("${ca}", createdAtFormat);
                String addLastMessageQuery = "UPDATE contacts SET messages_not_readed=${mnr}, last_message='${lm}', last_message_created=${lmc} WHERE _id='${_id}'".replace("${_id}", contactId).replace("${lm}", lastMessageJson).replace("${lmc}", tsString).replace("${mnr}", msNotRead);
                // Log.i(LOG_TAG, "SQLiteDatabase addLastMessageQuery: " + addLastMessageQuery);
                db.execSQL(addLastMessageQuery);

                // TIP: insert message in messages table
                Cursor c = db.rawQuery("SELECT id FROM contacts WHERE _id='"+contactId+"'", null);
                if (c.moveToFirst()) {
                    String localContactId = c.getString(0);
                    // Log.i(LOG_TAG, "SQLiteDatabase cursor contacts: " + localContactId);

                    if(!messageId.equals("")){
                        String text = rawText;
                        String image = "";
                        String audio = "";
                        String correct = "";
                        if(rawText.startsWith("formattedMessage=")){
                            String textToJson = rawText.replace("formattedMessage=", "");
                            JSONObject textParsed = new JSONObject(textToJson);
                            String type = textParsed.getString("type");
                            String source = "";
                            String data = "";
                            if(textParsed.has("source"))
                                source = textParsed.getString("source");
                            if(textParsed.has("data"))
                                data = textParsed.getString("data");
                            if (type.equals("image")) {
                                text = "";
                                image = source;
                            } else if (type.equals("audio")) {
                                text = "";
                                audio = source;
                            } else if (type.equals("correct")) {
                                text = "";
                                correct = data;
                            }
                        }
                        String addMessageQuery = "INSERT INTO 'messages'('id','_changed','_status','_id','archive_id','audio','correct','created','image','marker','text','user','contact_id','created_at','updated_at')" +
                                        "VALUES ('${mId}','','created','${mId}',NULL,'${urlAudio}','${dataCorrect}',${created},'${urlImage}','{\"type\":\"markable\"}','${rawText}','{\"_id\":\"${uId}\"}','${lcId}',${created},0.0)"
                                        .replace("${mId}", messageId).replace("${uId}", contactId).replace("${created}", tsString).replace("${lcId}", localContactId).replace("${rawText}", text).replace("${urlAudio}", audio).replace("${dataCorrect}", correct).replace("${urlImage}", image);
                        db.execSQL(addMessageQuery);
                    }
                }


            }


        } catch (Exception e) {
            Log.e(LOG_TAG, "failed to send push notification", e);
        }
    }

    private void scheduleNextNotificationIfRepeating(Bundle bundle) {
        String repeatType = bundle.getString("repeatType");
        long repeatTime = (long) bundle.getDouble("repeatTime");

        if (repeatType != null) {
            long fireDate = (long) bundle.getDouble("fireDate");

            boolean validRepeatType = Arrays.asList("time", "month", "week", "day", "hour", "minute").contains(repeatType);

            // Sanity checks
            if (!validRepeatType) {
                Log.w(LOG_TAG, String.format("Invalid repeatType specified as %s", repeatType));
                return;
            }

            if ("time".equals(repeatType) && repeatTime <= 0) {
                Log.w(LOG_TAG, "repeatType specified as time but no repeatTime " +
                        "has been mentioned");
                return;
            }

            long newFireDate = 0;

            switch (repeatType) {
                case "time":
                    newFireDate = fireDate + repeatTime;
                    break;
                case "month":
                    final Calendar fireDateCalendar = new GregorianCalendar();
                    fireDateCalendar.setTime(new Date(fireDate));
                    final int fireDay = fireDateCalendar.get(Calendar.DAY_OF_MONTH);
                    final int fireMinute = fireDateCalendar.get(Calendar.MINUTE);
                    final int fireHour = fireDateCalendar.get(Calendar.HOUR_OF_DAY);

                    final Calendar nextEvent = new GregorianCalendar();
                    nextEvent.setTime(new Date());
                    final int currentMonth = nextEvent.get(Calendar.MONTH);
                    int nextMonth = currentMonth < 11 ? (currentMonth + 1) : 0;
                    nextEvent.set(Calendar.YEAR, nextEvent.get(Calendar.YEAR) + (nextMonth == 0 ? 1 : 0));
                    nextEvent.set(Calendar.MONTH, nextMonth);
                    final int maxDay = nextEvent.getActualMaximum(Calendar.DAY_OF_MONTH);
                    nextEvent.set(Calendar.DAY_OF_MONTH, fireDay <= maxDay ? fireDay : maxDay);
                    nextEvent.set(Calendar.HOUR_OF_DAY, fireHour);
                    nextEvent.set(Calendar.MINUTE, fireMinute);
                    nextEvent.set(Calendar.SECOND, 0);
                    newFireDate = nextEvent.getTimeInMillis();
                    break;
                case "week":
                    newFireDate = fireDate + 7 * ONE_DAY;
                    break;
                case "day":
                    newFireDate = fireDate + ONE_DAY;
                    break;
                case "hour":
                    newFireDate = fireDate + ONE_HOUR;
                    break;
                case "minute":
                    newFireDate = fireDate + ONE_MINUTE;
                    break;
            }

            // Sanity check, should never happen
            if (newFireDate != 0) {
                Log.d(LOG_TAG, String.format("Repeating notification with id %s at time %s",
                        bundle.getString("id"), Long.toString(newFireDate)));
                bundle.putDouble("fireDate", newFireDate);
                this.sendNotificationScheduled(bundle);
            }
        }
    }

    public void clearNotifications() {
        Log.i(LOG_TAG, "Clearing alerts from the notification centre");

        NotificationManager notificationManager = notificationManager();
        notificationManager.cancelAll();
    }

    public void clearNotification(int notificationID) {
        Log.i(LOG_TAG, "Clearing notification: " + notificationID);

        NotificationManager notificationManager = notificationManager();
        notificationManager.cancel(notificationID);
    }

    public void cancelAllScheduledNotifications() {
        Log.i(LOG_TAG, "Cancelling all notifications");

        for (String id : scheduledNotificationsPersistence.getAll().keySet()) {
            cancelScheduledNotification(id);
        }
    }

    public void cancelScheduledNotification(ReadableMap userInfo) {
        for (String id : scheduledNotificationsPersistence.getAll().keySet()) {
            try {
                String notificationAttributesJson = scheduledNotificationsPersistence.getString(id, null);
                if (notificationAttributesJson != null) {
                    RNPushNotificationAttributes notificationAttributes = fromJson(notificationAttributesJson);
                    if (notificationAttributes.matches(userInfo)) {
                        cancelScheduledNotification(id);
                    }
                }
            } catch (JSONException e) {
                Log.w(LOG_TAG, "Problem dealing with scheduled notification " + id, e);
            }
        }
    }

    private void cancelScheduledNotification(String notificationIDString) {
        Log.i(LOG_TAG, "Cancelling notification: " + notificationIDString);

        // remove it from the alarm manger schedule
        Bundle b = new Bundle();
        b.putString("id", notificationIDString);
        getAlarmManager().cancel(toScheduleNotificationIntent(b));

        if (scheduledNotificationsPersistence.contains(notificationIDString)) {
            // remove it from local storage
            SharedPreferences.Editor editor = scheduledNotificationsPersistence.edit();
            editor.remove(notificationIDString);
            commit(editor);
        } else {
            Log.w(LOG_TAG, "Unable to find notification " + notificationIDString);
        }

        // removed it from the notification center
        NotificationManager notificationManager = notificationManager();

        notificationManager.cancel(Integer.parseInt(notificationIDString));
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (Build.VERSION.SDK_INT < 9) {
            editor.commit();
        } else {
            editor.apply();
        }
    }

    private static boolean channelCreated = false;
    private void checkOrCreateChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O)
            return;
        if (channelCreated)
            return;
        if (manager == null)
            return;

        Bundle bundle = new Bundle();

        int importance = NotificationManager.IMPORTANCE_HIGH;
        final String importanceString = bundle.getString("importance");

        if (importanceString != null) {
            switch(importanceString.toLowerCase()) {
                case "default":
                    importance = NotificationManager.IMPORTANCE_DEFAULT;
                    break;
                case "max":
                    importance = NotificationManager.IMPORTANCE_MAX;
                    break;
                case "high":
                    importance = NotificationManager.IMPORTANCE_HIGH;
                    break;
                case "low":
                    importance = NotificationManager.IMPORTANCE_LOW;
                    break;
                case "min":
                    importance = NotificationManager.IMPORTANCE_MIN;
                    break;
                case "none":
                    importance = NotificationManager.IMPORTANCE_NONE;
                    break;
                case "unspecified":
                    importance = NotificationManager.IMPORTANCE_UNSPECIFIED;
                    break;
                default:
                    importance = NotificationManager.IMPORTANCE_HIGH;
            }
        }

        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, this.config.getChannelName() != null ? this.config.getChannelName() : "rn-push-notification-channel", importance);

        channel.setDescription(this.config.getChannelDescription());
        channel.enableLights(true);
        channel.enableVibration(true);

        manager.createNotificationChannel(channel);
        channelCreated = true;
    }
}
