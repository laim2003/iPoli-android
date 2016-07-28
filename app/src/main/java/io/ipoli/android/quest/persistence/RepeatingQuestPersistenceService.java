package io.ipoli.android.quest.persistence;

import java.util.List;

import io.ipoli.android.app.persistence.PersistenceService;
import io.ipoli.android.challenge.data.Challenge;
import io.ipoli.android.reminders.data.Reminder;
import io.ipoli.android.quest.data.RepeatingQuest;

/**
 * Created by Venelin Valkov <venelin@curiousily.com>
 * on 3/31/16.
 */
public interface RepeatingQuestPersistenceService extends PersistenceService<RepeatingQuest> {

    List<RepeatingQuest> findAllNonAllDayActiveRepeatingQuests();

    void findAllNonAllDayActiveRepeatingQuests(OnDatabaseChangedListener<List<RepeatingQuest>> listener);

    void findNonFlexibleNonAllDayActiveRepeatingQuests(OnDatabaseChangedListener<List<RepeatingQuest>> listener);

    RepeatingQuest findByExternalSourceMappingId(String source, String sourceId);

    List<RepeatingQuest> findAllForChallenge(Challenge challenge);

    void saveReminders(RepeatingQuest repeatingQuest, List<Reminder> reminders);

    void findActiveForChallenge(Challenge challenge, OnDatabaseChangedListener<List<RepeatingQuest>> listener);

    List<RepeatingQuest> findActiveNotForChallenge(String query, Challenge challenge);

    List<RepeatingQuest> findNotDeleted(Challenge challenge);
}
