package co.chatsdk.firebase;

import co.chatsdk.core.dao.Keys;
import co.chatsdk.core.dao.User;
import co.chatsdk.core.types.ChatError;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import co.chatsdk.core.NM;

import co.chatsdk.core.StorageManager;
import co.chatsdk.core.defines.FirebaseDefines;
import co.chatsdk.core.handlers.SearchHandler;
import co.chatsdk.firebase.wrappers.UserWrapper;
import io.reactivex.Completable;
import io.reactivex.CompletableObserver;
import io.reactivex.CompletableSource;
import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Action;

/**
 * Created by benjaminsmiley-andrews on 24/05/2017.
 */

public class FirebaseSearchHandler implements SearchHandler {

    /** Indexing
     * To allow searching we're going to implement a simple index. Strings can be registered and
     * associated with users i.e. if there's a user called John Smith we could make a new index
     * like this:
     *
     * indexes/[index ID (priority is: johnsmith)]/[entity ID of John Smith]
     *
     * This will allow us to find the user*/
    //@Override
    public Observable<User> usersForIndex2(final String index, final String value) {
        return Observable.create(new ObservableOnSubscribe<User>() {
            @Override
            public void subscribe(final ObservableEmitter<User> e) throws Exception {
                if (StringUtils.isBlank(value))
                {
                    e.onError(ChatError.getError(ChatError.Code.NULL, "Value is blank"));
                    return;
                }

                Query query = FirebasePaths.indexRef().orderByChild(index).startAt(
                        processForQuery(value)).limitToFirst(FirebaseDefines.NumberOfUserToLoadForIndex);

                query.addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot snapshot) {
                        if (snapshot.getValue() != null) {

                            Map<String, Objects> values = (Map<String, Objects>) snapshot.getValue();

                            final List<User> usersToGo = new ArrayList<User>();
                            List<String> keys = new ArrayList<String>();

                            // So we dont have to call the db for each key.
                            String currentUserEntityID = NM.currentUser().getEntityID();

                            // Adding all keys to the list, Except the current user key.
                            for (String key : values.keySet())
                                if (!key.equals(currentUserEntityID))
                                    keys.add(key);

                            // Fetch or create users in the local db.
                            User bUser;
                            if (keys.size() > 0) {
                                for (String entityID : keys) {
                                    // Making sure that we wont try to get users with a null object id in the index section
                                    // If we will try the query will never end and there would be no result from the index.
                                    if(StringUtils.isNotBlank(entityID) && !entityID.equals(Keys.Null) && !entityID.equals("(null)"))
                                    {
                                        bUser = StorageManager.shared().fetchOrCreateEntityWithEntityID(User.class, entityID);
                                        usersToGo.add(bUser);
                                    }
                                }

                                ArrayList<Completable> completables = new ArrayList<>();

                                for (final User user : usersToGo) {

                                    completables.add(UserWrapper.initWithModel(user).once().andThen(new CompletableSource() {
                                        @Override
                                        public void subscribe(final CompletableObserver cs) {

                                            // Notify that a user has been found.
                                            // Making sure the user due start with the wanted name
                                            if (processForQuery(user.metaStringForKey(index)).startsWith(processForQuery(value))) {
                                                cs.onComplete();
                                            }
                                            else {

                                                // Remove the not valid user from the list.
                                                usersToGo.remove(user);
                                                cs.onComplete();
                                            }
                                        }
                                    }).doOnComplete(new Action() {
                                        @Override
                                        public void run() throws Exception {
                                            e.onNext(user);
                                        }
                                    }));
                                }

                                Completable.merge(completables).doOnComplete(new Action() {
                                    @Override
                                    public void run() throws Exception {
                                        e.onComplete();
                                    }
                                }).subscribe();

                            }
                            else {
                                e.onError(ChatError.getError(ChatError.Code.NO_USER_FOUND, "Unable to found user."));
                            }
                        } else {
                            e.onError(ChatError.getError(ChatError.Code.NO_USER_FOUND, "Unable to found user."));
                        }
                    }

                    @Override
                    public void onCancelled(DatabaseError firebaseError) {
                        e.onError(firebaseError.toException());
                    }
                });
            }
        });
    }

    public Observable<User> usersForIndex(final String index, final String value) {
        return Observable.create(new ObservableOnSubscribe<User>() {
            @Override
            public void subscribe(final ObservableEmitter<User> e) throws Exception {

                if (StringUtils.isBlank(value))
                {
                    e.onError(ChatError.getError(ChatError.Code.NULL, "Value is blank"));
                    return;
                }

                final Query query = FirebasePaths.usersRef()
                        .orderByChild(Keys.Meta + '/' + index)
                        .startAt(value)
                        .limitToFirst(FirebaseDefines.NumberOfUserToLoadForIndex);

                final ChildEventListener listener = query.addChildEventListener(new FirebaseEventListener().onChildAdded(new FirebaseEventListener.Change() {
                    @Override
                    public void trigger(DataSnapshot snapshot, String s, boolean hasValue) {
                        final UserWrapper wrapper = new UserWrapper(snapshot);
                        if(!wrapper.getModel().equals(NM.currentUser())) {
                            e.onNext(wrapper.getModel());
                        }
                    }
                }));

                e.setDisposable(new Disposable() {
                    @Override
                    public void dispose() {
                        query.removeEventListener(listener);
                    }

                    @Override
                    public boolean isDisposed() {
                        return false;
                    }
                });
            }
        });
    }

    public static String processForQuery(String query){
        return StringUtils.isBlank(query) ? "" : query.replace(" ", "").toLowerCase();
    }

}