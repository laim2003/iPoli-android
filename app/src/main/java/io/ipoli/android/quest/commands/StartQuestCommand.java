package io.ipoli.android.quest.commands;

import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationManagerCompat;

import java.util.List;
import java.util.concurrent.TimeUnit;

import io.ipoli.android.Constants;
import io.ipoli.android.app.utils.DateUtils;
import io.ipoli.android.quest.QuestNotificationScheduler;
import io.ipoli.android.quest.data.Quest;
import io.ipoli.android.quest.persistence.QuestPersistenceService;
import io.ipoli.android.quest.receivers.ScheduleQuestReminderReceiver;
import rx.Observable;

/**
 * Created by Venelin Valkov <venelin@curiousily.com>
 * on 2/2/16.
 */
public class StartQuestCommand {

    private final Context context;
    private final Quest quest;
    private final QuestPersistenceService questPersistenceService;

    public StartQuestCommand(Context context, Quest quest, QuestPersistenceService questPersistenceService) {
        this.context = context;
        this.quest = quest;
        this.questPersistenceService = questPersistenceService;
    }

    public Observable<Quest> execute() {
        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(context);
        notificationManagerCompat.cancel(Constants.REMIND_START_QUEST_NOTIFICATION_ID);
        quest.setActualStart(DateUtils.nowUTC());
        return questPersistenceService.save(quest).flatMap(q -> {
            scheduleNextQuestReminder(context);
            stopOtherRunningQuests(quest);

            if (quest.getDuration() > 0) {
                long durationMillis = TimeUnit.MINUTES.toMillis(quest.getDuration());
                long showDoneAtMillis = quest.getActualStart().getTime() + durationMillis;
                QuestNotificationScheduler.scheduleDone(quest.getId(), showDoneAtMillis, context);
            }
            return Observable.just(q);
        });
    }

    private void stopOtherRunningQuests(Quest q) {
        List<Quest> quests = questPersistenceService.findAllPlannedAndStartedToday();
        for (Quest cq : quests) {
            if (!cq.getId().equals(q.getId()) && Quest.isStarted(cq)) {
                cq.setActualStart(null);
                questPersistenceService.save(cq).subscribe();
            }
        }
    }

    private void scheduleNextQuestReminder(Context context) {
        context.sendBroadcast(new Intent(ScheduleQuestReminderReceiver.ACTION_SCHEDULE_REMINDER));
    }
}
