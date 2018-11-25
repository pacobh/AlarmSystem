/*
 * Copyright 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package fjbermudez.com.alarmsistem;

import android.content.Context;
import android.os.Build;
import android.os.CountDownTimer;
import android.support.annotation.RequiresApi;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.firebase.ui.database.FirebaseRecyclerAdapter;
import com.firebase.ui.database.FirebaseRecyclerOptions;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import java.util.HashMap;
import java.util.Map;

import fjbermudez.com.alarmsistem.model.DoorbellEntry;

/**
 * RecyclerView adapter to populate doorbell entries from Firebase.
 */
public class DoorbellEntryAdapter extends FirebaseRecyclerAdapter<DoorbellEntry, DoorbellEntryAdapter.DoorbellEntryViewHolder> {

    private DatabaseReference databaseReference;
    private final String KEY_ACCEPT_ENTRANCE_VALUE = "acceptEntrance";


    /**
     * ViewHolder for each doorbell entry
     */
    public static class DoorbellEntryViewHolder extends RecyclerView.ViewHolder {

        public final ImageView image;
        public final TextView time;
        public final Button acceptEntrance;
        public final Button deniedEntrance;

//        public final TextView metadata;

        public DoorbellEntryViewHolder(View itemView) {
            super(itemView);

            this.image = (ImageView) itemView.findViewById(R.id.imageView1);
            this.time = (TextView) itemView.findViewById(R.id.textView1);
            this.acceptEntrance = (Button) itemView.findViewById(R.id.btAccept);
            this.deniedEntrance = (Button) itemView.findViewById(R.id.btDenied);

//            this.metadata = (TextView) itemView.findViewById(R.id.textView2);
        }
    }

    private Context mApplicationContext;
    private FirebaseStorage mFirebaseStorage;

    public DoorbellEntryAdapter(Context context, DatabaseReference ref) {
        super(new FirebaseRecyclerOptions.Builder<DoorbellEntry>()
                .setQuery(ref, DoorbellEntry.class)
                .build());

        mApplicationContext = context.getApplicationContext();
        mFirebaseStorage = FirebaseStorage.getInstance();
        databaseReference = ref;
    }

    @Override
    public DoorbellEntryViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View entryView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.doorbell_entry, parent, false);

        return new DoorbellEntryViewHolder(entryView);
    }

    @RequiresApi(api = Build.VERSION_CODES.CUPCAKE)
    @Override
    protected void onBindViewHolder(DoorbellEntryViewHolder holder, int position, final DoorbellEntry model) {

        // Display the timestamp
        CharSequence prettyTime = DateUtils.getRelativeDateTimeString(mApplicationContext,
                model.getTimestamp(), DateUtils.SECOND_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0);
        holder.time.setText(prettyTime);

        // Display the image
        if (model.getImage() != null) {
            StorageReference imageRef = mFirebaseStorage.getReferenceFromUrl(model.getImage());

            GlideApp.with(mApplicationContext)
                    .load(imageRef)
                    .placeholder(R.drawable.ic_image)
                    .into(holder.image);
        }

        // onClickListenerButtons

        holder.acceptEntrance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                acceptEntrance(model);
            }
        });

        holder.deniedEntrance.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deniedEntrance(model);
            }
        });

        // Display the metadata
//        if (model.getAnnotations() != null) {
//            ArrayList<String> keywords = new ArrayList<>(model.getAnnotations().keySet());
//
//            int limit = Math.min(keywords.size(), 3);
//            holder.metadata.setText(TextUtils.join("\n", keywords.subList(0, limit)));
//        } else {
//            holder.metadata.setText("no annotations yet");
//        }
    }

    private void acceptEntrance(DoorbellEntry model){

        Map<String,Object> acceptMap = new HashMap<String,Object>();
        acceptMap.put(KEY_ACCEPT_ENTRANCE_VALUE, true);
        databaseReference.child(model.getKey()).updateChildren(acceptMap);

    }
    private void deniedEntrance(DoorbellEntry model){

        Map<String,Object> deniedMap = new HashMap<String,Object>();
        deniedMap.put(KEY_ACCEPT_ENTRANCE_VALUE, false);
        databaseReference.child(model.getKey()).updateChildren(deniedMap);

    }

}
