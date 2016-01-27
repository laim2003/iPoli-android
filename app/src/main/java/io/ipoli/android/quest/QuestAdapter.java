package io.ipoli.android.quest;

import android.content.Context;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.helper.ItemTouchHelper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.squareup.otto.Bus;
import com.wefika.flowlayout.FlowLayout;

import java.util.Collections;
import java.util.List;

import io.ipoli.android.R;
import io.ipoli.android.app.ui.ItemTouchHelperAdapter;
import io.ipoli.android.app.ui.ItemTouchHelperViewHolder;
import io.ipoli.android.quest.events.ChangeQuestOrderEvent;
import io.ipoli.android.quest.events.EditQuestRequestEvent;
import io.ipoli.android.quest.events.QuestCompleteRequestEvent;
import io.ipoli.android.quest.events.QuestUpdatedEvent;
import io.ipoli.android.quest.events.StartQuestEvent;
import io.ipoli.android.quest.events.StopQuestEvent;

/**
 * Created by Venelin Valkov <venelin@curiousily.com>
 * on 1/9/16.
 */
public class QuestAdapter extends RecyclerView.Adapter<QuestAdapter.ViewHolder> implements ItemTouchHelperAdapter {

    private Context context;
    private List<Quest> quests;
    private final Bus eventBus;

    public QuestAdapter(Context context, List<Quest> quests, Bus eventBus) {
        this.context = context;
        this.quests = quests;
        this.eventBus = eventBus;
    }

    @Override
    public QuestAdapter.ViewHolder onCreateViewHolder(ViewGroup parent,
                                                      int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.quest_item, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, final int position) {
        final Quest q = quests.get(position);

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eventBus.post(new EditQuestRequestEvent(q.getId(), position));
            }
        });

        FlowLayout flowLayout = (FlowLayout) holder.itemView.findViewById(R.id.flow_layout);

        TextView nameView = (TextView) flowLayout.findViewById(R.id.quest_name);
        nameView.setText(q.getName());

        View durationView = LayoutInflater.from(context).inflate(R.layout.template_quest_property, null);
        durationView.setBackgroundColor(ContextCompat.getColor(context, R.color.md_blue_500));
        ImageView durationImage = (ImageView) durationView.findViewById(R.id.quest_property_image);
        durationImage.setImageResource(R.drawable.ic_alarm_white_24dp);
        TextView durationText = (TextView) durationView.findViewById(R.id.quest_property_text);
        durationText.setText(" 30 minutes ");

        View startTimeView = LayoutInflater.from(context).inflate(R.layout.template_quest_property, null);
        startTimeView.setBackgroundColor(ContextCompat.getColor(context, R.color.md_green_500));
        ImageView startTimeImage = (ImageView) startTimeView.findViewById(R.id.quest_property_image);
        startTimeImage.setImageResource(R.drawable.ic_alarm_white_24dp);
        TextView startTimeText = (TextView) startTimeView.findViewById(R.id.quest_property_text);
        startTimeText.setText(" 12:30 ");

        TextView prepositionTextFor = (TextView) LayoutInflater.from(context).inflate(R.layout.template_quest_preposition_text, null);
        prepositionTextFor.setText(" for ");

        TextView prepositionTextAt = (TextView) LayoutInflater.from(context).inflate(R.layout.template_quest_preposition_text, null);
        prepositionTextAt.setText(" at ");



        FlowLayout.LayoutParams params = new FlowLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER_VERTICAL;

        durationView.setLayoutParams(params);
        startTimeView.setLayoutParams(params);
        prepositionTextAt.setLayoutParams(params);
        prepositionTextFor.setLayoutParams(params);

        flowLayout.addView(prepositionTextFor);
        flowLayout.addView(durationView);
        flowLayout.addView(prepositionTextAt);
        flowLayout.addView(startTimeView);



        final ImageButton startBtn = (ImageButton) holder.itemView.findViewById(R.id.quest_start);
        if (Status.valueOf(q.getStatus()) == Status.STARTED) {
            startBtn.setImageResource(R.drawable.ic_stop_outline_accent_24dp);
        } else if (Status.valueOf(q.getStatus()) == Status.PLANNED) {
            startBtn.setImageResource(R.drawable.ic_play_circle_outline_accent_24dp);
        }
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Status status = Status.valueOf(q.getStatus());
                if (status == Status.PLANNED) {
                    updateQuestStatus(q, Status.STARTED);
                } else if (status == Status.STARTED) {
                    updateQuestStatus(q, Status.PLANNED);
                }
                notifyItemChanged(holder.getAdapterPosition());
            }
        });
        holder.itemView.findViewById(R.id.quest_done_tick).setVisibility(View.GONE);
    }

    public void updateQuestStatus(Quest quest, Status status) {
        int questIndex = getQuestIndex(quest);
        if (questIndex < 0) {
            return;
        }
        Quest q = quests.get(questIndex);
        Status oldStatus = Status.valueOf(q.getStatus());
        q.setStatus(status.name());
        if (status == Status.COMPLETED) {
            onItemDismissed(questIndex, ItemTouchHelper.RIGHT);
            return;
        }
        if (status == Status.PLANNED && oldStatus == Status.STARTED) {
            eventBus.post(new StopQuestEvent(q));
            notifyItemChanged(questIndex);
        } else if (status == Status.STARTED && oldStatus == Status.PLANNED) {
            stopOtherRunningQuests(q);
            eventBus.post(new StartQuestEvent(q));
            notifyItemChanged(questIndex);
        }
        eventBus.post(new QuestUpdatedEvent(q));
    }

    private void stopOtherRunningQuests(Quest q) {
        for (int i = 0; i < quests.size(); i++) {
            Quest cq = quests.get(i);
            if (cq != q && Status.valueOf(cq.getStatus()) == Status.STARTED) {
                cq.setStatus(Status.PLANNED.name());
                eventBus.post(new StopQuestEvent(cq));
                notifyItemChanged(i);
            }
        }
    }

    private int getQuestIndex(Quest quest) {
        for (int i = 0; i < quests.size(); i++) {
            Quest q = quests.get(i);
            if (q.getId().equals(quest.getId())) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getItemCount() {
        return quests.size();
    }

    public void putQuest(int position, Quest quest) {
        quests.add(position, quest);
        notifyItemInserted(position);
    }

    public void updateQuest(int position, Quest quest) {
        quests.set(position, quest);
        notifyItemChanged(position);
    }

    public List<Quest> getQuests() {
        return quests;
    }

    @Override
    public void onItemMoved(int fromPosition, int toPosition) {
        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(quests, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(quests, i, i - 1);
            }
        }
        eventBus.post(new ChangeQuestOrderEvent(quests.get(toPosition)));
        notifyItemMoved(fromPosition, toPosition);
    }

    @Override
    public void onItemDismissed(int position, int direction) {
        Quest q = quests.get(position);
        quests.remove(position);
        notifyItemRemoved(position);
        eventBus.post(new QuestCompleteRequestEvent(q, position));
    }

    public void addQuest(Quest quest) {
        quests.add(quest);
        notifyItemInserted(quests.size() - 1);
    }

    public void removeQuest(int position) {
        quests.remove(position);
        notifyItemRemoved(position);
    }

    public static class ViewHolder extends RecyclerView.ViewHolder implements ItemTouchHelperViewHolder {

        public ViewHolder(View v) {
            super(v);
        }

        @Override
        public void onItemSelected() {
            itemView.setBackgroundResource(R.color.md_blue_100);
        }

        @Override
        public void onItemClear() {
            itemView.setBackgroundColor(0);
        }

        @Override
        public void onItemSwipeStart() {
            itemView.findViewById(R.id.quest_done_tick).setVisibility(View.VISIBLE);
        }

        @Override
        public void onItemSwipeStopped() {
            itemView.findViewById(R.id.quest_done_tick).setVisibility(View.GONE);
        }
    }
}