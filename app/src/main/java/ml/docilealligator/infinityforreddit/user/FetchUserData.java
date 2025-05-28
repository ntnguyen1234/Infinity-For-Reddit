package ml.docilealligator.infinityforreddit.user;

import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.apis.RedditAPI;
import ml.docilealligator.infinityforreddit.thing.SortType;
import ml.docilealligator.infinityforreddit.utils.APIUtils;
import ml.docilealligator.infinityforreddit.utils.JSONUtils;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

public class FetchUserData {
    public static void fetchUserData(Executor executor, Handler handler, Retrofit retrofit, String userName, FetchUserDataListener fetchUserDataListener) {
        fetchUserData(executor, handler, null, retrofit, null, userName, fetchUserDataListener);
    }

    public static void fetchUserData(Executor executor, Handler handler, RedditDataRoomDatabase redditDataRoomDatabase, Retrofit retrofit,
                                     String accessToken, String userName, FetchUserDataListener fetchUserDataListener) {
        RedditAPI api = retrofit.create(RedditAPI.class);

        Call<String> userInfo;
        if (redditDataRoomDatabase == null) {
            userInfo = api.getUserData(userName);
        } else {
            userInfo = api.getUserDataOauth(APIUtils.getOAuthHeader(accessToken), userName);
        }

        userInfo.enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (response.isSuccessful()) {
                    executor.execute(() -> {
                        try {
                            JSONObject jsonResponse = new JSONObject(response.body());
                            UserData userData = parseUserDataBase(jsonResponse, true);
                            if (redditDataRoomDatabase != null) {
                                redditDataRoomDatabase.accountDao().updateAccountInfo(userData.getName(), userData.getIconUrl(), userData.getBanner(), userData.getTotalKarma());
                            }
                            if (jsonResponse.getJSONObject(JSONUtils.DATA_KEY).has(JSONUtils.INBOX_COUNT_KEY)) {
                                int inboxCount = jsonResponse.getJSONObject(JSONUtils.DATA_KEY).getInt(JSONUtils.INBOX_COUNT_KEY);
                                handler.post(() -> fetchUserDataListener.onFetchUserDataSuccess(userData, inboxCount));
                            } else {
                                handler.post(() -> fetchUserDataListener.onFetchUserDataSuccess(userData, -1));
                            }
                        } catch (JSONException e) {
                            e.printStackTrace();
                            handler.post(fetchUserDataListener::onFetchUserDataFailed);
                        }
                    });
                } else {
                    fetchUserDataListener.onFetchUserDataFailed();
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable throwable) {
                fetchUserDataListener.onFetchUserDataFailed();
            }
        });
    }

    public static void fetchUserListingData(Executor executor, Handler handler, Retrofit retrofit, String query, String after, SortType.Type sortType, boolean nsfw,
                                            FetchUserListingDataListener fetchUserListingDataListener) {
        RedditAPI api = retrofit.create(RedditAPI.class);

        Call<String> userInfo = api.searchUsers(query, after, sortType, nsfw ? 1 : 0);
        final String[] responseString = {null};
        userInfo.enqueue(new Callback<>() {
            @Override
            public void onResponse(@NonNull Call<String> call, @NonNull Response<String> response) {
                if (response.isSuccessful()) {
                    executor.execute(() -> {
                        try {
                            responseString[0] = response.body();
                            JSONObject jsonResponse = new JSONObject(responseString[0]);
                            String newAfter = jsonResponse.getJSONObject(JSONUtils.DATA_KEY).getString(JSONUtils.AFTER_KEY);
                            JSONArray children = jsonResponse.getJSONObject(JSONUtils.DATA_KEY).getJSONArray(JSONUtils.CHILDREN_KEY);
                            List<UserData> userDataList = new ArrayList<>();
                            for (int i = 0; i < children.length(); i++) {
                                try {
                                    UserData userData = parseUserDataBase(children.getJSONObject(i), false);
                                    userDataList.add(userData);
                                } catch (JSONException e) {
                                    e.printStackTrace();
                                }
                            }
                            handler.post(() -> fetchUserListingDataListener.onFetchUserListingDataSuccess(userDataList, newAfter));
                        } catch (JSONException e) {
                            handler.post(() -> {
                                if (responseString[0] != null && responseString[0].equals("\"{}\"")) {
                                    fetchUserListingDataListener.onFetchUserListingDataSuccess(new ArrayList<>(), null);
                                } else {
                                    fetchUserListingDataListener.onFetchUserListingDataFailed();
                                }
                            });
                        }
                    });
                } else {
                    fetchUserListingDataListener.onFetchUserListingDataFailed();
                }
            }

            @Override
            public void onFailure(@NonNull Call<String> call, @NonNull Throwable throwable) {
                fetchUserListingDataListener.onFetchUserListingDataFailed();
            }
        });
    }

    @WorkerThread
    private static UserData parseUserDataBase(JSONObject userDataJson, boolean parseFullKarma) throws JSONException {
        if (userDataJson == null) {
            return null;
        }

        userDataJson = userDataJson.getJSONObject(JSONUtils.DATA_KEY);
        String userName = userDataJson.getString(JSONUtils.NAME_KEY);
        String iconImageUrl = userDataJson.getString(JSONUtils.ICON_IMG_KEY);
        String bannerImageUrl = "";
        boolean canBeFollowed;
        if (userDataJson.has(JSONUtils.SUBREDDIT_KEY) && !userDataJson.isNull(JSONUtils.SUBREDDIT_KEY)) {
            bannerImageUrl = userDataJson.getJSONObject(JSONUtils.SUBREDDIT_KEY).getString(JSONUtils.BANNER_IMG_KEY);
            canBeFollowed = true;
        } else {
            canBeFollowed = false;
        }
        int linkKarma = userDataJson.getInt(JSONUtils.LINK_KARMA_KEY);
        int commentKarma = userDataJson.getInt(JSONUtils.COMMENT_KARMA_KEY);
        int awarderKarma = 0;
        int awardeeKarma = 0;
        int totalKarma = linkKarma + commentKarma;
        if (parseFullKarma) {
            awarderKarma = userDataJson.getInt(JSONUtils.AWARDER_KARMA_KEY);
            awardeeKarma = userDataJson.getInt(JSONUtils.AWARDEE_KARMA_KEY);
            totalKarma = userDataJson.getInt(JSONUtils.TOTAL_KARMA_KEY);
        }
        long cakeday = userDataJson.getLong(JSONUtils.CREATED_UTC_KEY) * 1000;
        boolean isGold = userDataJson.getBoolean(JSONUtils.IS_GOLD_KEY);
        boolean isFriend = userDataJson.getBoolean(JSONUtils.IS_FRIEND_KEY);
        boolean isNsfw = userDataJson.getJSONObject(JSONUtils.SUBREDDIT_KEY).getBoolean(JSONUtils.OVER_18_KEY);
        String description = userDataJson.getJSONObject(JSONUtils.SUBREDDIT_KEY).getString(JSONUtils.PUBLIC_DESCRIPTION_KEY);
        String title = userDataJson.getJSONObject(JSONUtils.SUBREDDIT_KEY).getString(JSONUtils.TITLE_KEY);

        return new UserData(userName, iconImageUrl, bannerImageUrl, linkKarma, commentKarma, awarderKarma,
                awardeeKarma, totalKarma, cakeday, isGold, isFriend, canBeFollowed, isNsfw, description, title);
    }

    public interface FetchUserDataListener {
        void onFetchUserDataSuccess(UserData userData, int inboxCount);

        void onFetchUserDataFailed();
    }

    public interface FetchUserListingDataListener {
        void onFetchUserListingDataSuccess(List<UserData> userData, String after);

        void onFetchUserListingDataFailed();
    }
}
