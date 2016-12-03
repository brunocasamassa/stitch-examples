package com.mongodb.baas.sdk.examples.todo;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;

import com.facebook.AccessToken;
import com.facebook.CallbackManager;
import com.facebook.FacebookCallback;
import com.facebook.FacebookException;
import com.facebook.FacebookSdk;
import com.facebook.login.LoginManager;
import com.facebook.login.LoginResult;
import com.facebook.login.widget.LoginButton;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Continuation;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.mongodb.baas.sdk.AuthListener;
import com.mongodb.baas.sdk.BaasClient;
import com.mongodb.baas.sdk.auth.Auth;
import com.mongodb.baas.sdk.auth.AuthProviderInfo;
import com.mongodb.baas.sdk.auth.facebook.FacebookAuthProvider;
import com.mongodb.baas.sdk.auth.google.GoogleAuthProvider;
import com.mongodb.baas.sdk.services.mongodb.MongoClient;

import org.bson.Document;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import static com.google.android.gms.auth.api.Auth.*;
import static com.mongodb.baas.sdk.services.mongodb.MongoClient.*;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "TodoApp";
    private static final String APP_NAME = "todo";
    private static final long REFRESH_INTERVAL_MILLIS = 1000;
    private static final int RC_SIGN_IN = 421;

    private CallbackManager _callbackManager;
    private GoogleApiClient _googleApiClient;
    private BaasClient _client;
    private MongoClient _mongoClient;

    private TodoListAdapter _itemAdapter;
    private Handler _handler;
    private Runnable _refresher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        _handler = new Handler();
        _refresher = new ListRefresher(this);

        _client = new BaasClient(this, APP_NAME, "http://erd.ngrok.io");
        _client.addAuthListener(new MyAuthListener(this));
        _mongoClient = new MongoClient(_client, "mdb1");
        initLogin();
    }

    private static class MyAuthListener extends AuthListener {

        private WeakReference<MainActivity> _main;

        public MyAuthListener(final MainActivity activity) {
            _main = new WeakReference<>(activity);
        }

        @Override
        public void onLogin() {
            Log.d(TAG, "Logged into BaaS");
        }

        @Override
        public void onLogout() {
            final MainActivity activity = _main.get();
            if (activity != null) {
                activity._handler.removeCallbacks(activity._refresher);
                activity.initLogin();
            }
        }
    }

    private static class ListRefresher implements Runnable {

        private WeakReference<MainActivity> _main;

        public ListRefresher(final MainActivity activity) {
            _main = new WeakReference<>(activity);
        }

        @Override
        public void run() {
            final MainActivity activity = _main.get();
            if (activity != null && activity._client.isAuthed()) {
                activity.refreshList().addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull final Task<Void> ignored) {
                        activity._handler.postDelayed(ListRefresher.this, REFRESH_INTERVAL_MILLIS);
                    }
                });
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == RC_SIGN_IN) {
            final GoogleSignInResult result = GoogleSignInApi.getSignInResultFromIntent(data);
            handleGooglSignInResult(result);
            return;
        }

        if (_callbackManager != null) {
            _callbackManager.onActivityResult(requestCode, resultCode, data);
            return;
        }
        Log.e(TAG, "Nowhere to send activity result for ourselves");
    }

    private void handleGooglSignInResult(final GoogleSignInResult result) {
        if (result == null) {
            Log.e(TAG, "Got a null GoogleSignInResult");
            return;
        }

        Log.d(TAG, "handleGooglSignInResult:" + result.isSuccess());
        if (result.isSuccess()) {
            final GoogleAuthProvider googleProvider =
                    GoogleAuthProvider.fromIdToken(result.getSignInAccount().getServerAuthCode());
            _client.logInWithProvider(googleProvider).addOnCompleteListener(new OnCompleteListener<Auth>() {
                @Override
                public void onComplete(@NonNull final Task<Auth> task) {
                    if (task.isSuccessful()) {
                        initTodoView();
                    } else {
                        Log.e(TAG, "Error logging in with Google", task.getException());
                    }
                }
            });
        }
    }

    private void initLogin() {
        _client.getAuthProviders().addOnCompleteListener(new OnCompleteListener<AuthProviderInfo>() {
            @Override
            public void onComplete(@NonNull final Task<AuthProviderInfo> task) {
                if (task.isSuccessful()) {
                    setupLogin(task.getResult());
                } else {
                    Log.e(TAG, "Error getting auth info", task.getException());
                    // Maybe retry here...
                }
            }
        });
    }

    private void initTodoView() {
        setContentView(R.layout.activity_main_todo_list);

        // Set up items
        _itemAdapter = new TodoListAdapter(
                this,
                R.layout.todo_item,
                new ArrayList<TodoItem>(),
                getItemsCollection());
        ((ListView) findViewById(R.id.todoList)).setAdapter(_itemAdapter);

        // Set up button listeners
        findViewById(R.id.refresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View ignored) {
                refreshList();
            }
        });

        findViewById(R.id.clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View ignored) {
                clearChecked();
            }
        });

        findViewById(R.id.logout).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View ignored) {
                _client.logout();
            }
        });

        findViewById(R.id.addItem).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View ignored) {
                final AlertDialog.Builder diagBuilder = new AlertDialog.Builder(MainActivity.this);

                final LayoutInflater inflater = MainActivity.this.getLayoutInflater();
                final View view = inflater.inflate(R.layout.add_item, null);
                final EditText text = (EditText) view.findViewById(R.id.addItemText);

                diagBuilder.setView(view);
                diagBuilder.setPositiveButton(R.string.addOk, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialogInterface, final int i) {
                        addItem(text.getText().toString());
                    }
                });
                diagBuilder.setNegativeButton(R.string.addCancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialogInterface, final int i) {
                        dialogInterface.cancel();
                    }
                });
                diagBuilder.setCancelable(false);
                diagBuilder.create().show();
            }
        });

        _refresher.run();
    }

    private void addItem(final String text) {
        final Document doc = new Document();
        doc.put("user", _client.getAuth().getUser().getId());
        doc.put("text", text);

        getItemsCollection().insertOne(doc).addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull final Task<Void> task) {
                if (task.isSuccessful()) {
                    refreshList();
                } else {
                    Log.e(TAG, "Error adding item", task.getException());
                }
            }
        });
    }

    private void clearChecked() {
        final Document query = new Document();
        query.put("user", _client.getAuth().getUser().getId());
        query.put("checked", true);

        getItemsCollection().deleteMany(query).addOnCompleteListener(new OnCompleteListener<Void>() {
            @Override
            public void onComplete(@NonNull final Task<Void> task) {
                if (task.isSuccessful()) {
                    refreshList();
                } else {
                    Log.e(TAG, "Error clearing checked items", task.getException());
                }
            }
        });
    }

    private Collection getItemsCollection() {
        return _mongoClient.getDatabase("todo").getCollection("items");
    }

    private List<TodoItem> convertDocsToTodo(final List<Document> documents) {
        final List<TodoItem> items = new ArrayList<>(documents.size());
        for (final Document doc : documents) {
            items.add(new TodoItem(doc));
        }
        return items;
    }

    private Task<Void> refreshList() {
        return getItemsCollection().findMany().continueWithTask(new Continuation<List<Document>, Task<Void>>() {
            @Override
            public Task<Void> then(@NonNull final Task<List<Document>> task) throws Exception {
                if (task.isSuccessful()) {
                    final List<Document> documents = task.getResult();
                    _itemAdapter.clear();
                    _itemAdapter.addAll(convertDocsToTodo(documents));
                    _itemAdapter.notifyDataSetChanged();
                    return Tasks.forResult(null);
                } else {
                    Log.e(TAG, "Error refreshing list", task.getException());
                    return Tasks.forException(task.getException());
                }
            }
        });
    }

    private void setupLogin(final AuthProviderInfo info) {

        if (_client.isAuthed()) {
            initTodoView();
            return;
        }

        final List<Task<Void>> initFutures = new ArrayList<>();

        if (info.hasFacebook()) {
            FacebookSdk.setApplicationId(info.getFacebook().getApplicationId());
            final TaskCompletionSource<Void> fbInitFuture = new TaskCompletionSource<>();
            FacebookSdk.sdkInitialize(getApplicationContext(), new FacebookSdk.InitializeCallback() {
                @Override
                public void onInitialized() {
                    fbInitFuture.setResult(null);
                }
            });
            initFutures.add(fbInitFuture.getTask());
        }

        Tasks.whenAll(initFutures).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(final Void ignored) {
                setContentView(R.layout.activity_main);

                if (info.hasFacebook()) {
                    final LoginButton loginButton = (LoginButton) findViewById(R.id.login_button);
                    loginButton.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(final View ignored) {

                            // Check if already logged in
                            if (AccessToken.getCurrentAccessToken() != null) {
                                final FacebookAuthProvider fbProvider =
                                        FacebookAuthProvider.fromAccessToken(AccessToken.getCurrentAccessToken().getToken());
                                _client.logInWithProvider(fbProvider).addOnCompleteListener(new OnCompleteListener<Auth>() {
                                    @Override
                                    public void onComplete(@NonNull final Task<Auth> task) {
                                        if (task.isSuccessful()) {
                                            initTodoView();
                                        } else {
                                            Log.e(TAG, "Error logging in with Facebook", task.getException());
                                        }
                                    }
                                });
                                return;
                            }

                            _callbackManager = CallbackManager.Factory.create();
                            LoginManager.getInstance().registerCallback(_callbackManager,
                                    new FacebookCallback<LoginResult>() {
                                        @Override
                                        public void onSuccess(LoginResult loginResult) {
                                            final FacebookAuthProvider fbProvider =
                                                    FacebookAuthProvider.fromAccessToken(loginResult.getAccessToken().getToken());

                                            _client.logInWithProvider(fbProvider).addOnCompleteListener(new OnCompleteListener<Auth>() {
                                                @Override
                                                public void onComplete(@NonNull final Task<Auth> task) {
                                                    if (task.isSuccessful()) {
                                                        initTodoView();
                                                    } else {
                                                        Log.e(TAG, "Error logging in with Facebook", task.getException());
                                                    }
                                                }
                                            });
                                        }

                                        @Override
                                        public void onCancel() {}

                                        @Override
                                        public void onError(final FacebookException exception) {
                                            initTodoView();
                                        }
                                    });
                            LoginManager.getInstance().logInWithReadPermissions(
                                    MainActivity.this,
                                    info.getFacebook().getScopes());
                        }
                    });
                }

                if (info.hasGoogle()) {
                    final GoogleSignInOptions.Builder gsoBuilder = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestServerAuthCode(info.getGoogle().getClientId(), false);
                    for (final Scope scope: info.getGoogle().getScopes()) {
                        gsoBuilder.requestScopes(scope);
                    }
                    final GoogleSignInOptions gso = gsoBuilder.build();

                    if (_googleApiClient != null) {
                        _googleApiClient.stopAutoManage(MainActivity.this);
                        _googleApiClient.disconnect();
                    }

                    _googleApiClient = new GoogleApiClient.Builder(MainActivity.this)
                            .enableAutoManage(MainActivity.this, new GoogleApiClient.OnConnectionFailedListener() {
                                @Override
                                public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                                    Log.e(TAG, "Error connecting to google: " + connectionResult.getErrorMessage());
                                }
                            })
                            .addApi(GOOGLE_SIGN_IN_API, gso)
                            .build();

                    findViewById(R.id.sign_in_button).setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(final View ignored) {
                            final Intent signInIntent =
                                    GoogleSignInApi.getSignInIntent(_googleApiClient);
                            startActivityForResult(signInIntent, RC_SIGN_IN);
                        }
                    });
                }
            }
        });
    }
}