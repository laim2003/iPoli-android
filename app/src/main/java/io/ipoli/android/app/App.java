package io.ipoli.android.app;

import android.Manifest;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.multidex.MultiDexApplication;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;

import com.facebook.FacebookSdk;
import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;

import net.danlew.android.joda.JodaTimeAndroid;

import org.joda.time.LocalDate;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import io.ipoli.android.APIConstants;
import io.ipoli.android.BuildConfig;
import io.ipoli.android.Constants;
import io.ipoli.android.R;
import io.ipoli.android.app.events.CurrentDayChangedEvent;
import io.ipoli.android.app.events.EventSource;
import io.ipoli.android.app.events.ForceServerSyncRequestEvent;
import io.ipoli.android.app.events.ScheduleRepeatingQuestsEvent;
import io.ipoli.android.app.events.ServerSyncRequestEvent;
import io.ipoli.android.app.events.SyncCalendarRequestEvent;
import io.ipoli.android.app.events.UndoCompletedQuestEvent;
import io.ipoli.android.app.events.VersionUpdatedEvent;
import io.ipoli.android.app.modules.AppModule;
import io.ipoli.android.app.modules.RestAPIModule;
import io.ipoli.android.app.services.AnalyticsService;
import io.ipoli.android.app.services.AppJobService;
import io.ipoli.android.app.services.events.SyncCompleteEvent;
import io.ipoli.android.app.services.readers.AndroidCalendarQuestListReader;
import io.ipoli.android.app.services.readers.AndroidCalendarRepeatingQuestListReader;
import io.ipoli.android.app.utils.DateUtils;
import io.ipoli.android.app.utils.LocalStorage;
import io.ipoli.android.app.utils.Time;
import io.ipoli.android.player.ExperienceForLevelGenerator;
import io.ipoli.android.player.Player;
import io.ipoli.android.player.events.LevelDownEvent;
import io.ipoli.android.player.events.LevelUpEvent;
import io.ipoli.android.player.persistence.PlayerPersistenceService;
import io.ipoli.android.player.persistence.RealmPlayerPersistenceService;
import io.ipoli.android.quest.QuestNotificationScheduler;
import io.ipoli.android.quest.data.Quest;
import io.ipoli.android.quest.data.Recurrence;
import io.ipoli.android.quest.data.RepeatingQuest;
import io.ipoli.android.quest.events.CompleteQuestRequestEvent;
import io.ipoli.android.quest.events.NewQuestEvent;
import io.ipoli.android.quest.events.NewRepeatingQuestEvent;
import io.ipoli.android.quest.events.QuestCompletedEvent;
import io.ipoli.android.quest.events.RepeatingQuestSavedEvent;
import io.ipoli.android.quest.events.UndoCompletedQuestRequestEvent;
import io.ipoli.android.quest.events.UpdateQuestEvent;
import io.ipoli.android.quest.persistence.QuestPersistenceService;
import io.ipoli.android.quest.persistence.RealmQuestPersistenceService;
import io.ipoli.android.quest.persistence.RealmRepeatingQuestPersistenceService;
import io.ipoli.android.quest.persistence.RepeatingQuestPersistenceService;
import io.ipoli.android.quest.persistence.events.QuestDeletedEvent;
import io.ipoli.android.quest.persistence.events.QuestSavedEvent;
import io.ipoli.android.quest.persistence.events.QuestsDeletedEvent;
import io.ipoli.android.quest.persistence.events.RepeatingQuestDeletedEvent;
import io.ipoli.android.quest.receivers.ScheduleQuestReminderReceiver;
import io.ipoli.android.quest.schedulers.RepeatingQuestScheduler;
import io.ipoli.android.quest.ui.events.UpdateRepeatingQuestEvent;
import io.ipoli.android.quest.widgets.AgendaWidgetProvider;
import io.ipoli.android.tutorial.events.TutorialDoneEvent;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import me.everything.providers.android.calendar.Calendar;
import me.everything.providers.android.calendar.CalendarProvider;
import me.everything.providers.android.calendar.Event;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.schedulers.Schedulers;

/**
 * Created by Venelin Valkov <venelin@curiousily.com>
 * on 1/7/16.
 */
public class App extends MultiDexApplication {

    private static final int SYNC_JOB_ID = 1;
    private static final int DAILY_SYNC_JOB_ID = 2;

    private static AppComponent appComponent;

    @Inject
    Bus eventBus;

    @Inject
    RepeatingQuestScheduler repeatingQuestScheduler;

    @Inject
    AnalyticsService analyticsService;

    QuestPersistenceService questPersistenceService;

    RepeatingQuestPersistenceService repeatingQuestPersistenceService;

    PlayerPersistenceService playerPersistenceService;

    BroadcastReceiver dateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            scheduleQuestsFor2WeeksAhead().compose(applyAndroidSchedulers()).subscribe();
            eventBus.post(new CurrentDayChangedEvent(new LocalDate(), CurrentDayChangedEvent.Source.CALENDAR));
            resetEndDateForIncompleteQuests();
            updateWidgets();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        JodaTimeAndroid.init(this);
        FacebookSdk.sdkInitialize(getApplicationContext());

        RealmConfiguration config = new RealmConfiguration.Builder(this)
                .schemaVersion(BuildConfig.VERSION_CODE)
                .deleteRealmIfMigrationNeeded()
                .initialData(realm -> {
                    Player player = new Player(String.valueOf(Constants.DEFAULT_PLAYER_XP), Constants.DEFAULT_PLAYER_LEVEL, Constants.DEFAULT_PLAYER_AVATAR);
                    player.setCoins(Constants.DEFAULT_PLAYER_COINS);
                    realm.copyToRealm(player);
                })
//                .migration((realm, oldVersion, newVersion) -> {
//
//                })
                .build();
        Realm.setDefaultConfiguration(config);

        getAppComponent(this).inject(this);

        Realm realm = Realm.getDefaultInstance();
        questPersistenceService = new RealmQuestPersistenceService(eventBus, realm);
        repeatingQuestPersistenceService = new RealmRepeatingQuestPersistenceService(eventBus, realm);
        playerPersistenceService = new RealmPlayerPersistenceService(realm);

        resetEndDateForIncompleteQuests();
        registerServices();
        scheduleNextReminder();

        LocalStorage localStorage = LocalStorage.of(getApplicationContext());
        int versionCode = localStorage.readInt(Constants.KEY_APP_VERSION_CODE);
        if (versionCode != BuildConfig.VERSION_CODE) {
            scheduleJob(dailySyncJob());
            localStorage.saveInt(Constants.KEY_APP_VERSION_CODE, BuildConfig.VERSION_CODE);
            eventBus.post(new VersionUpdatedEvent(versionCode, BuildConfig.VERSION_CODE));
        }
        scheduleQuestsFor2WeeksAhead().compose(applyAndroidSchedulers()).subscribe(aVoid -> {
        }, Throwable::printStackTrace, () -> {
            if (localStorage.readInt(Constants.KEY_APP_RUN_COUNT) != 0) {
                eventBus.post(new ForceServerSyncRequestEvent());
            }
            localStorage.increment(Constants.KEY_APP_RUN_COUNT);
        });

        getApplicationContext().registerReceiver(dateChangedReceiver, new IntentFilter(Intent.ACTION_DATE_CHANGED));

//        if (BuildConfig.DEBUG) {
//            StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
//                    .detectDiskReads()
//                    .detectDiskWrites()
//                    .detectNetwork()   // or .detectAll() for all detectable problems
//                    .penaltyLog()
//                    .build());
//            StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
//                    .detectLeakedSqlLiteObjects()
//                    .detectLeakedClosableObjects()
//                    .penaltyLog()
//                    .penaltyDeath()
//                    .build());
//        }
    }

    private Observable<Void> scheduleQuestsFor2WeeksAhead() {
        return Observable.defer(() -> {
            Realm realm = Realm.getDefaultInstance();
            QuestPersistenceService questPersistenceService = new RealmQuestPersistenceService(eventBus, realm);
            RepeatingQuestPersistenceService repeatingQuestPersistenceService = new RealmRepeatingQuestPersistenceService(eventBus, realm);
            List<RepeatingQuest> repeatingQuests = repeatingQuestPersistenceService.findAllNonAllDayActiveRepeatingQuests();
            for (RepeatingQuest rq : repeatingQuests) {
                saveQuestsForRepeatingQuest(rq, questPersistenceService);
            }
            realm.close();
            return Observable.empty();
        });
    }

    private void saveQuestsInRange(RepeatingQuest rq, LocalDate startOfWeek, LocalDate endOfWeek, QuestPersistenceService questPersistenceService) {
        long createdQuestsCount = questPersistenceService.countAllForRepeatingQuest(rq, startOfWeek, endOfWeek);
        if (createdQuestsCount == 0) {
            List<Quest> questsToCreate = repeatingQuestScheduler.schedule(rq, DateUtils.toStartOfDayUTC(startOfWeek));
            questPersistenceService.saveSync(questsToCreate);
        }
    }

    private void resetEndDateForIncompleteQuests() {
        List<Quest> quests = questPersistenceService.findAllIncompleteToDosBefore(new LocalDate());
        for (Quest q : quests) {
            if (q.isStarted()) {
                q.setEndDateFromLocal(new Date());
                q.setStartMinute(0);
            } else {
                q.setEndDate(null);
            }
            questPersistenceService.save(q).subscribe();
        }
    }

    private void registerServices() {
        eventBus.register(analyticsService);
        eventBus.register(this);
    }

    public static AppComponent getAppComponent(Context context) {
        if (appComponent == null) {
            String ipoliApiBaseUrl = BuildConfig.DEBUG ? APIConstants.DEV_IPOLI_ENDPOINT : APIConstants.PROD_IPOLI_ENDPOINT;
            String schedulingApiBaseUrl = BuildConfig.DEBUG ? APIConstants.DEV_SCHEDULING_ENDPOINT : APIConstants.PROD_SCHEDULING_ENDPOINT;

            appComponent = DaggerAppComponent.builder()
                    .appModule(new AppModule(context))
                    .restAPIModule(new RestAPIModule(ipoliApiBaseUrl, schedulingApiBaseUrl))
                    .build();
        }
        return appComponent;
    }

    @Subscribe
    public void onScheduleRepeatingQuests(ScheduleRepeatingQuestsEvent e) {
        scheduleQuestsFor2WeeksAhead().compose(applyAndroidSchedulers()).subscribe(quests -> {
        }, Throwable::printStackTrace, () -> {
            eventBus.post(new SyncCompleteEvent());
        });
    }

    @Subscribe
    public void onQuestCompleteRequest(CompleteQuestRequestEvent e) {
        Quest q = e.quest;
        QuestNotificationScheduler.stopAll(q.getId(), this);
        q.setCompletedAt(new Date());
        q.setCompletedAtMinute(Time.now().toMinutesAfterMidnight());
        questPersistenceService.save(q).subscribe(this::onQuestComplete);
    }

    @Subscribe
    public void onUndoCompletedQuestRequest(UndoCompletedQuestRequestEvent e) {
        Quest quest = e.quest;
        // @TODO remove old logs
        quest.getLogs().clear();
        quest.setDifficulty(null);
        quest.setActualStart(null);
        quest.setCompletedAt(null);
        quest.setCompletedAtMinute(null);

        if (quest.isScheduledForThePast()) {
            quest.setEndDate(null);
            quest.setStartDate(null);
        }
        questPersistenceService.save(quest).subscribe(q -> {
            Player player = playerPersistenceService.find();
            player.removeExperience(q.getExperience());
            if (shouldDecreaseLevel(player)) {
                player.setLevel(Math.max(Constants.DEFAULT_PLAYER_LEVEL, player.getLevel() - 1));
                while (shouldDecreaseLevel(player)) {
                    player.setLevel(Math.max(Constants.DEFAULT_PLAYER_LEVEL, player.getLevel() - 1));
                }
                eventBus.post(new LevelDownEvent(player.getLevel()));
            }
            player.removeCoins(q.getCoins());
            playerPersistenceService.saveSync(player);
            eventBus.post(new UndoCompletedQuestEvent(q));
        });
    }

    private boolean shouldIncreaseLevel(Player player) {
        return new BigInteger(player.getExperience()).compareTo(ExperienceForLevelGenerator.forLevel(player.getLevel() + 1)) >= 0;
    }

    private boolean shouldDecreaseLevel(Player player) {
        return new BigInteger(player.getExperience()).compareTo(ExperienceForLevelGenerator.forLevel(player.getLevel())) < 0;
    }

    @Subscribe
    public void onNewQuest(NewQuestEvent e) {
        questPersistenceService.save(e.quest).subscribe(quest -> {
            if (quest.getCompletedAt() != null) {
                onQuestComplete(quest);
            }
        });
    }

    @Subscribe
    public void onUpdateQuest(UpdateQuestEvent e) {
        questPersistenceService.save(e.quest).subscribe(quest -> {
            if (quest.getCompletedAt() != null) {
                onQuestComplete(quest);
            }
        });
    }

    @Subscribe
    public void onUpdateRepeatingQuest(UpdateRepeatingQuestEvent e) {
        repeatingQuestPersistenceService.save(e.repeatingQuest).subscribe();
    }

    private void onQuestComplete(Quest quest) {
        Player player = playerPersistenceService.find();
        player.addExperience(quest.getExperience());
        if (shouldIncreaseLevel(player)) {
            player.setLevel(player.getLevel() + 1);
            while (shouldIncreaseLevel(player)) {
                player.setLevel(player.getLevel() + 1);
            }
            eventBus.post(new LevelUpEvent(player.getLevel()));
        }
        player.addCoins(quest.getCoins());
        playerPersistenceService.saveSync(player);
        eventBus.post(new QuestCompletedEvent(quest, EventSource.ADD_QUEST));
    }

    @Subscribe
    public void onNewRepeatingQuest(NewRepeatingQuestEvent e) {
        repeatingQuestPersistenceService.save(e.repeatingQuest).subscribe();
    }

    @Subscribe
    public void onRepeatingQuestSaved(RepeatingQuestSavedEvent e) {
        RepeatingQuest rq = e.repeatingQuest;

        Recurrence recurrence = rq.getRecurrence();
        if (TextUtils.isEmpty(recurrence.getRrule())) {
            List<Quest> questsToCreate = new ArrayList<>();
            for (int i = 0; i < recurrence.getTimesPerDay(); i++) {
                questsToCreate.add(repeatingQuestScheduler.createQuestFromRepeating(rq, recurrence.getDtstart()));
            }
            questPersistenceService.saveRemoteObjects(questsToCreate).subscribe(quests -> {
            }, Throwable::printStackTrace, () -> {
                eventBus.post(new ServerSyncRequestEvent());
            });
        } else {

            Observable.defer(() -> {
                Realm realm = Realm.getDefaultInstance();
                QuestPersistenceService questPersistenceService = new RealmQuestPersistenceService(eventBus, realm);
                saveQuestsForRepeatingQuest(rq, questPersistenceService);
                realm.close();
                return Observable.empty();
            }).compose(applyAndroidSchedulers()).subscribe(quests -> {
            }, Throwable::printStackTrace, () -> {
                eventBus.post(new ServerSyncRequestEvent());
            });
        }
    }

    private void saveQuestsForRepeatingQuest(RepeatingQuest repeatingQuest, QuestPersistenceService questPersistenceService) {
        LocalDate currentDate = LocalDate.now();
        LocalDate startOfWeek = currentDate.dayOfWeek().withMinimumValue();
        LocalDate endOfWeek = currentDate.dayOfWeek().withMaximumValue();
        LocalDate startOfNextWeek = startOfWeek.plusDays(7);
        LocalDate endOfNextWeek = endOfWeek.plusDays(7);
        saveQuestsInRange(repeatingQuest, startOfWeek, endOfWeek, questPersistenceService);
        saveQuestsInRange(repeatingQuest, startOfNextWeek, endOfNextWeek, questPersistenceService);
    }

    @Subscribe
    public void onQuestSaved(QuestSavedEvent e) {
        eventBus.post(new ServerSyncRequestEvent());
        onQuestChanged();
    }

    @Subscribe
    public void onQuestsDeleted(QuestsDeletedEvent e) {
        List<Quest> quests = e.quests;
        LocalStorage localStorage = LocalStorage.of(getApplicationContext());
        Set<String> removedQuests = localStorage.readStringSet(Constants.KEY_REMOVED_QUESTS);
        for(Quest quest : quests) {
            QuestNotificationScheduler.stopAll(quest.getId(), this);
            removedQuests.add(quest.getId());
        }
        localStorage.saveStringSet(Constants.KEY_REMOVED_QUESTS, removedQuests);
        questPersistenceService.delete(quests).subscribe(ignored -> {
        }, Throwable::printStackTrace, () -> {
            eventBus.post(new ServerSyncRequestEvent());
            onQuestChanged();
        });


    }

    @Subscribe
    public void onQuestDeleted(QuestDeletedEvent e) {
        QuestNotificationScheduler.stopAll(e.id, this);
        eventBus.post(new ServerSyncRequestEvent());
        onQuestChanged();
    }

    private void onQuestChanged() {
        scheduleNextReminder();
        updateWidgets();
    }

    private void updateWidgets() {
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);
        int appWidgetIds[] = appWidgetManager.getAppWidgetIds(
                new ComponentName(this, AgendaWidgetProvider.class));
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_agenda_list);
    }

    @Subscribe
    public void onRepeatingQuestDeleted(RepeatingQuestDeletedEvent e) {
        eventBus.post(new ServerSyncRequestEvent());
        scheduleNextReminder();
    }

    @Subscribe
    public void onSyncRequest(ServerSyncRequestEvent e) {
        scheduleJob(defaultSyncJob()
                .build());
    }

    private void scheduleJob(JobInfo job) {
        getJobScheduler().schedule(job);
    }

    private JobScheduler getJobScheduler() {
        return (JobScheduler)
                getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    @Subscribe
    public void onForceSyncRequest(ForceServerSyncRequestEvent e) {
        scheduleJob(createJobBuilder(SYNC_JOB_ID).setPersisted(true)
                .setBackoffCriteria(JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL).
                        setOverrideDeadline(1).build());
    }

    private JobInfo.Builder defaultSyncJob() {
        return createJobBuilder(SYNC_JOB_ID).setPersisted(true)
                .setMinimumLatency(TimeUnit.MINUTES.toMillis(Constants.MINIMUM_DELAY_SYNC_MINUTES))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(JobInfo.DEFAULT_INITIAL_BACKOFF_MILLIS, JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    }

    private JobInfo dailySyncJob() {
        return createJobBuilder(DAILY_SYNC_JOB_ID).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setRequiresDeviceIdle(true)
                .setPeriodic(TimeUnit.HOURS.toMillis(24)).build();
    }

    @NonNull
    private JobInfo.Builder createJobBuilder(int dailySyncJobId) {
        return new JobInfo.Builder(dailySyncJobId,
                new ComponentName(getPackageName(),
                        AppJobService.class.getName()));
    }

    private void scheduleNextReminder() {
        sendBroadcast(new Intent(ScheduleQuestReminderReceiver.ACTION_SCHEDULE_REMINDER));
    }

    @Subscribe
    public void onTutorialDone(TutorialDoneEvent e) {
        if (ContextCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            eventBus.post(new ForceServerSyncRequestEvent());
            return;
        }

        syncCalendars().subscribe(o -> {
        }, Throwable::printStackTrace, () -> {
            eventBus.post(new SyncCompleteEvent());
            eventBus.post(new ForceServerSyncRequestEvent());
        });

    }

    @Subscribe
    public void onSyncWithCalendarRequest(SyncCalendarRequestEvent e) {
        if (ContextCompat.checkSelfPermission(getApplicationContext(),
                Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        syncCalendars().subscribe(o -> {
        }, Throwable::printStackTrace, () ->
                eventBus.post(new SyncCompleteEvent()));
    }

    private Observable<Object> syncCalendars() {
        return Observable.defer(() -> {
            Realm realm = Realm.getDefaultInstance();
            QuestPersistenceService questPersistenceService = new RealmQuestPersistenceService(eventBus, realm);
            RepeatingQuestPersistenceService repeatingQuestPersistenceService = new RealmRepeatingQuestPersistenceService(eventBus, realm);
            AndroidCalendarQuestListReader questReader = new AndroidCalendarQuestListReader(questPersistenceService, repeatingQuestPersistenceService);
            AndroidCalendarRepeatingQuestListReader repeatingQuestReader = new AndroidCalendarRepeatingQuestListReader(repeatingQuestPersistenceService);
            CalendarProvider provider = new CalendarProvider(this);
            List<Calendar> calendars = provider.getCalendars().getList();
            LocalStorage localStorage = LocalStorage.of(this);
            Set<String> calendarIds = new HashSet<>();
            List<Event> repeating = new ArrayList<>();
            List<Event> nonRepeating = new ArrayList<>();
            for (Calendar c : calendars) {
                if (!c.visible) {
                    continue;
                }
                calendarIds.add(String.valueOf(c.id));
                List<Event> events = provider.getEvents(c.id).getList();
                for (Event event : events) {
                    if (isRepeatingAndroidCalendarEvent(event)) {
                        repeating.add(event);
                    } else {
                        nonRepeating.add(event);
                    }
                }
            }
            localStorage.saveStringSet(Constants.KEY_SELECTED_ANDROID_CALENDARS, calendarIds);
            List<Quest> quests = questReader.read(nonRepeating);
            questPersistenceService.saveSync(quests);
            List<RepeatingQuest> repeatingQuests = repeatingQuestReader.read(repeating);
            repeatingQuestPersistenceService.saveSync(repeatingQuests);
            realm.close();
            return Observable.empty();
        }).compose(applyAndroidSchedulers());
    }

    private <T> Observable.Transformer<T, T> applyAndroidSchedulers() {
        return observable -> observable.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private boolean isRepeatingAndroidCalendarEvent(Event e) {
        return !TextUtils.isEmpty(e.rRule) || !TextUtils.isEmpty(e.rDate);
    }

    @Subscribe
    public void onSyncComplete(SyncCompleteEvent e) {
        scheduleNextReminder();
        updateWidgets();
    }
}