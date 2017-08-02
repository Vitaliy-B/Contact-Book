package bv.dev.sannacode.contactbook;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.AsyncTaskLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.SimpleAdapter;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.WrapperListAdapter;

import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.auth.api.signin.GoogleSignInResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.GoogleApiClient;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements GoogleApiClient.OnConnectionFailedListener,
        LoaderManager.LoaderCallbacks<List<? extends Map<String, ?>>> , IListItemPrefs, IContactsDBHolder {

    private static final String LOG_TAG = "bv_log";
    private static final String KEY_ERR_CODE = "KEY_ERR_CODE";
    private static final String KEY_CONTACT_EDIT_MODE = "KEY_CONTACT_EDIT_MODE";

    private static final String KEY_CONTACT_ID = "KEY_CONTACT_ID";
    private static final String KEY_USER_ID = "KEY_USER_ID";
    private static final String KEY_FIRST_NAME = "KEY_FIRST_NAME";
    private static final String KEY_LAST_NAME = "KEY_LAST_NAME";
    private static final String KEY_PHONES = "KEY_PHONES";
    private static final String KEY_EMAILS = "KEY_EMAILS";

    private static final int QUERY_CONTACTS = 0;
    private static final int REQUEST_CODE_SIGN_IN = 0;
    private static final int REQUEST_CODE_RESOLVE_ERR = 1;
    private static final int MODE_CONTACT_EDIT_NEW = 0;
    private static final int MODE_CONTACT_EDIT_UPDATE = 1;
    private static final int MODE_CONTACT_DELETE = 2;

    private String contUserId;
    private ContactsDB.ContactsSortOrder contSortOrder = ContactsDB.ContactsSortOrder.ByDate;
    private ContactsDB.ContactsSortDirection contSortDir = ContactsDB.ContactsSortDirection.ASC;
    private String listItemsMax = "100"; // anyway it will be converted to string in query

    private GoogleApiClient gac;
    private ContactsDB contactsDB;
    private ContactItemAdapter contactAdapter;
    private ArrayList<Map<String, ?>> listItems = new ArrayList<>();

    // to use google sign-in, apk must be signed by valid certificate
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.i(LOG_TAG, "-----------------------");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //ConstraintLayout clRoot = (ConstraintLayout) findViewById(R.id.layout_root); unused
        ListView lvContacts = (ListView) findViewById(R.id.lv_contacts);
        registerForContextMenu(lvContacts);
        View headerAdd = getLayoutInflater().inflate(R.layout.list_item_add, lvContacts, false);
        headerAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Bundle args = new Bundle(1);
                args.putInt(KEY_CONTACT_EDIT_MODE, MODE_CONTACT_EDIT_NEW);
                args.putInt(KEY_CONTACT_ID, 0);
                args.putString(KEY_USER_ID, contUserId);
                DialogContactEditFragment dcef = new DialogContactEditFragment();
                dcef.setArguments(args);
                dcef.show(getSupportFragmentManager(), DialogContactEditFragment.class.getSimpleName());
            }
        });
        lvContacts.addHeaderView(headerAdd);

        contactsDB = new ContactsDB(this);
        contactAdapter = new ContactItemAdapter(this, listItems, R.layout.list_contacts_item, null, null);
        lvContacts.setAdapter(contactAdapter);

        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build();
        gac = new GoogleApiClient.Builder(this)
                .enableAutoManage(this, this)
                .addApi(Auth.GOOGLE_SIGN_IN_API, gso)
                .build();
        Intent intentSignIn = Auth.GoogleSignInApi.getSignInIntent(gac);
        startActivityForResult(intentSignIn, REQUEST_CODE_SIGN_IN);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_item_sort_order:
                new DialogSortOrderFragment().show(getSupportFragmentManager(),
                        DialogSortOrderFragment.class.getSimpleName());
                break;
            case R.id.menu_item_sort_dir:
                new DialogSortDirFragment().show(getSupportFragmentManager(),
                        DialogSortDirFragment.class.getSimpleName());
                break;
            case R.id.menu_item_max_items:
                new DialogItemsMaxFragment().show(getSupportFragmentManager(),
                        DialogItemsMaxFragment.class.getSimpleName());
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        getMenuInflater().inflate(R.menu.menu_list_item_context, menu);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo acmi = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        Bundle args = new Bundle();
        try {
            args.putInt(KEY_CONTACT_EDIT_MODE, MODE_CONTACT_EDIT_UPDATE);
            Long contactID = (Long) (acmi.targetView.getTag(R.id.id_key_contact_id));
            args.putLong(KEY_CONTACT_ID, contactID);
            args.putString(KEY_USER_ID, (String) (acmi.targetView.getTag(R.id.id_key_user_id)));
            for(Map<String, ?> map : listItems) {
                if( map.get(KEY_CONTACT_ID).equals(contactID)) {
                    args.putString(KEY_FIRST_NAME, (String) map.get(KEY_FIRST_NAME));
                    args.putString(KEY_LAST_NAME, (String) map.get(KEY_LAST_NAME));
                    args.putStringArrayList(KEY_PHONES, (ArrayList) map.get(KEY_PHONES));
                    args.putStringArrayList(KEY_EMAILS, (ArrayList) map.get(KEY_EMAILS));
                    break;
                }
            }
        } catch (ClassCastException | NullPointerException e) {
            Log.e(LOG_TAG, "onContextItemSelected Exception ", e);
        }
        switch(item.getItemId()) {
            case R.id.menu_context_edit:
                DialogContactEditFragment dcef = new DialogContactEditFragment();
                dcef.setArguments(args);
                dcef.show(getSupportFragmentManager(), DialogContactEditFragment.class.getSimpleName());
                break;
            case R.id.menu_context_delete:
                new DBAsyncTask(this, contactsDB, MODE_CONTACT_DELETE, getSupportLoaderManager(), this)
                        .executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, args); // do not query DB in parallel
                break;
            default:
                return super.onContextItemSelected(item);
        }
        return true;
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connRes) {
        String msg = getString(R.string.msg_conn_fail) + ": " + connRes.getErrorMessage()
                + " (" + connRes.getErrorCode() + ") ";
        Log.e(LOG_TAG, msg + connRes);
        //Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); unused
        try {
            if(connRes.hasResolution()) {
                connRes.startResolutionForResult(this, REQUEST_CODE_RESOLVE_ERR);
                Toast.makeText(this, R.string.msg_resolving_err, Toast.LENGTH_LONG).show();
            } else {
                showFatalGApiError(connRes.getErrorCode());
            }
        } catch (IntentSender.SendIntentException sie) {
            showFatalGApiError(connRes.getErrorCode());
        }
    }

    private void showFatalGApiError(int errCode) {
        DialogGApiErrorFragment errFrag = new DialogGApiErrorFragment();
        Bundle args = new Bundle(1);
        args.putInt(KEY_ERR_CODE, errCode);
        errFrag.setArguments(args);
        errFrag.show(getSupportFragmentManager(), DialogGApiErrorFragment.class.getSimpleName());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode == REQUEST_CODE_SIGN_IN) {
            GoogleSignInResult gsiRes = Auth.GoogleSignInApi.getSignInResultFromIntent(data);
            if(gsiRes == null) {
                String msg = getString(R.string.msg_signin_fail);
                Log.e(LOG_TAG, msg + ". SignInResult == null");
                Toast.makeText(this, msg + ". SignInResult == null", Toast.LENGTH_LONG).show();
                finish();
            } else if(!gsiRes.isSuccess()) {
                String msg = getString(R.string.msg_signin_fail);
                Log.e(LOG_TAG, msg + ". " + gsiRes.getStatus() + "; "
                        + CommonStatusCodes.getStatusCodeString(gsiRes.getStatus().getStatusCode()));
                /* unused
                Toast.makeText(this,
                        msg + ". " + CommonStatusCodes.getStatusCodeString(gsiRes.getStatus().getStatusCode()),
                        Toast.LENGTH_LONG).show();
                        */
                try {
                    if(gsiRes.getStatus().hasResolution()) {
                        gsiRes.getStatus().startResolutionForResult(this, REQUEST_CODE_RESOLVE_ERR);
                        Toast.makeText(this, R.string.msg_resolving_err, Toast.LENGTH_LONG).show();
                    } else {
                        showFatalGApiError(gsiRes.getStatus().getStatusCode());
                    }
                } catch (IntentSender.SendIntentException sie) {
                    showFatalGApiError(gsiRes.getStatus().getStatusCode());
                }
            } else if(gsiRes.getSignInAccount() == null) {
                String msg = getString(R.string.msg_signin_acc_null);
                Log.e(LOG_TAG, msg + ". " + gsiRes.getStatus());
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                finish();
            } else {
                /* not needed
                Toast.makeText(this, getString(R.string.msg_signin_account)
                                + gsiRes.getSignInAccount().getDisplayName(), Toast.LENGTH_LONG).show();
                                */
                Log.i(LOG_TAG, "SignInAccount : " + gsiRes.getSignInAccount());

                final ImageView ivUser = (ImageView) findViewById(R.id.iv_user);
                TextView tvUserName = (TextView) findViewById(R.id.tv_user_name);
                TextView tvUserEmail = (TextView) findViewById(R.id.tv_user_email);

                contUserId = gsiRes.getSignInAccount().getId();
                //gsiRes.getSignInAccount().getIdToken(); // not requested
                tvUserName.setText(gsiRes.getSignInAccount().getDisplayName());
                tvUserEmail.setText(gsiRes.getSignInAccount().getEmail());
                getSupportLoaderManager().restartLoader(QUERY_CONTACTS, null, this);

                /* not needed
                Log.d(LOG_TAG, "SignInAccount PhotoUrl == "
                        + gsiRes.getSignInAccount().getPhotoUrl());*/
                //----------------
                // load profile image
                new AsyncTask<String, Void, Bitmap>() {
                    @Override
                    protected Bitmap doInBackground(String... strings) {
                        if(strings == null || strings.length == 0) {
                            return null;
                        }
                        try {
                            URL url = new URL(strings[0]);
                            return BitmapFactory.decodeStream(url.openConnection().getInputStream());
                        } catch(IOException ioe) {
                            Log.w(LOG_TAG, "Can't load profile image " + ioe);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(getApplicationContext(), R.string.text_cant_load,
                                            Toast.LENGTH_LONG).show();
                                }
                            });
                            return null;
                        }
                    }
                    @Override
                    protected void onPostExecute(Bitmap bitmap) {
                        super.onPostExecute(bitmap);
                        if(bitmap != null) {
                            ivUser.setImageBitmap(bitmap);
                        }
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "" + gsiRes.getSignInAccount().getPhotoUrl());
            }
        } else if(requestCode == REQUEST_CODE_RESOLVE_ERR) {
            startActivityForResult(Auth.GoogleSignInApi.getSignInIntent(gac), REQUEST_CODE_SIGN_IN);
        }
    }

    //--------------------------
    // dialogs fragment interface

    @Override
    public void setSortOrder(int order) {
        switch(order) {
            case 0:
                contSortOrder = ContactsDB.ContactsSortOrder.ByDate;
                break;
            case 1:
                contSortOrder = ContactsDB.ContactsSortOrder.ByName;
                break;
        }
        //reloadList(); old way
        getSupportLoaderManager().restartLoader(QUERY_CONTACTS, null, this);
    }

    @Override
    public void setSortDirection(int dir) {
        switch(dir) {
            case 0:
                contSortDir = ContactsDB.ContactsSortDirection.ASC;
                break;
            case 1:
                contSortDir = ContactsDB.ContactsSortDirection.DESC;
                break;
        }
        //reloadList(); old way
        getSupportLoaderManager().restartLoader(QUERY_CONTACTS, null, this);
    }

    @Override
    public void setItemsMaxCount(String max) {
        listItemsMax = max;
        //reloadList(); old way
        getSupportLoaderManager().restartLoader(QUERY_CONTACTS, null, this);
    }

    @Override
    public ContactsDB getContactsDB() {
        return contactsDB;
    }

    //--------------------------

    /* unused
    private void reloadList() {
        getSupportLoaderManager().restartLoader(QUERY_CONTACTS, null, this);
    }
    */

    /* debug
    private static void logCursor(Cursor crs) {
        Log.d(LOG_TAG, "--------------- Log Cursor");
        if(crs == null){
            Log.d(LOG_TAG, "cursor == null");
        } else {
            Log.d(LOG_TAG, Arrays.toString(crs.getColumnNames()));
            if(! crs.moveToFirst()) {
                Log.d(LOG_TAG, "cursor is empty");
            } else {
                StringBuilder strBldr = new StringBuilder();
                do {
                    strBldr.setLength(0);
                    for (int idxCol = 0; idxCol < crs.getColumnCount(); idxCol++) {
                        strBldr.append(crs.getString(idxCol)).append("; ");
                    }
                    Log.d(LOG_TAG, strBldr.toString());
                } while(crs.moveToNext());
            }
            crs.moveToFirst(); // to enable future use
        }
    }
    */

    //--------------------------
    //LoaderCallbacks
    @Override
    public Loader<List<? extends Map<String, ?>>> onCreateLoader(int id, Bundle args) {
        if(QUERY_CONTACTS == id) {
            Toast.makeText(this, R.string.text_loading, Toast.LENGTH_LONG).show();
            return new ContactAsyncLoader(this, contactsDB, contUserId, contSortOrder,
                    contSortDir, listItemsMax);
        }
        return null;
    }

    @Override
    public void onLoadFinished(Loader<List<? extends Map<String, ?>>> loader, List<? extends Map<String, ?>> data) {
        switch(loader.getId()) {
            case QUERY_CONTACTS:
                if (contactAdapter != null) {
                    listItems.clear();
                    listItems.addAll(data);
                    contactAdapter.notifyDataSetChanged();
                    if(data.size() == 0) {
                        Log.w(LOG_TAG, "onLoadFinished QUERY_CONTACTS : list empty or null");
                        Toast.makeText(this, R.string.text_nothing_to_show, Toast.LENGTH_LONG).show();
                    }
                }
                break;
        }
        Log.d(LOG_TAG, "LoaderCallbacks.onLoadFinished()");
    }

    @Override
    public void onLoaderReset(Loader<List<? extends Map<String, ?>>> loader) {
        switch(loader.getId()) {
            case QUERY_CONTACTS:
                if(contactAdapter != null) {
                    contactAdapter.notifyDataSetInvalidated();
                }
                break;
        }
        Log.d(LOG_TAG, "LoaderCallbacks.onLoaderReset()");
    }
    //--------------------------

    // custom loader for contact list adapter
    private static class ContactAsyncLoader extends AsyncTaskLoader<List<? extends Map<String, ?>>> {
        // better do not update adapter's data from another thread
        //private List<HashMap<String, Object>> data; // store loaded data (link to adapter's)
        private ContactsDB db;
        private String userID;
        private ContactsDB.ContactsSortOrder sortOrder;
        private ContactsDB.ContactsSortDirection sortDir;
        private String limit;

        // last could be Bundle
        ContactAsyncLoader(Context context, ContactsDB contactsDB,String userID,
                                  ContactsDB.ContactsSortOrder sortOrder,
                                  ContactsDB.ContactsSortDirection sortDir, String limit) {
            super(context);
            // to init smth else better use application's context: getContext()
            db = contactsDB;
            this.userID = userID;
            this.sortOrder = sortOrder;
            this.sortDir = sortDir;
            this.limit = limit;
        }

        @Override
        public List<? extends Map<String, ?>> loadInBackground() {
            if(isLoadInBackgroundCanceled() || db == null) {
                Log.d(LOG_TAG, "ContactAsyncLoader : Load canceled ? ; db == " + db);
                return null;
            }

            Cursor crsContacts = null;
            Cursor crsPhones = null;
            Cursor crsEmails = null;
            /* debug
            Cursor crsContactsTest = null;
            Cursor crsPhonesTest = null;
            Cursor crsEmailsTest = null;
            */
            try {
                crsContacts = db.queryContacts(userID, sortOrder, sortDir, limit);
                List<HashMap<String, Object>> data = new ArrayList<>();
                /* outdated
                if(data != null) {
                    data.clear();
                } else {
                    data = new ArrayList<>();
                } */
                while (crsContacts.moveToNext()) {
                    HashMap<String, Object> mapItem = new HashMap<>(crsContacts.getColumnCount());
                    long contactID = crsContacts.getLong(crsContacts.getColumnIndex(ContactsDB.TabContacts.COL_ID));
                    mapItem.put(KEY_CONTACT_ID, contactID);
                    mapItem.put(KEY_USER_ID,
                            crsContacts.getString(crsContacts.getColumnIndex(ContactsDB.TabContacts.COL_USER_ID)));
                    mapItem.put(KEY_FIRST_NAME,
                            crsContacts.getString(crsContacts.getColumnIndex(ContactsDB.TabContacts.COL_NAME_FIRST)));
                    mapItem.put(KEY_LAST_NAME,
                            crsContacts.getString(crsContacts.getColumnIndex(ContactsDB.TabContacts.COL_NAME_LAST)));

                    if(crsPhones != null) {
                        crsPhones.close();
                    }
                    crsPhones = db.queryPhones(contactID);

                    ArrayList<String> alPhones = new ArrayList<>(crsPhones.getCount());
                    while(crsPhones.moveToNext()) {
                        alPhones.add(crsPhones.getString(crsPhones.getColumnIndex(ContactsDB.TabPhones.COL_PHONE)));
                    }
                    mapItem.put(KEY_PHONES, alPhones);

                    if(crsEmails != null) {
                        crsEmails.close();
                    }
                    crsEmails = db.queryEmails(contactID);

                    ArrayList<String> alEmails = new ArrayList<>(crsEmails.getCount());
                    while(crsEmails.moveToNext()) {
                        alEmails.add(crsEmails.getString(crsEmails.getColumnIndex(ContactsDB.TabEmails.COL_EMAIL)));
                    }
                    mapItem.put(KEY_EMAILS, alEmails);

                    data.add(mapItem);

                    /* debug
                    logCursor(crsPhones);
                    logCursor(crsEmails);
                    */
                }

                /* debug
                logCursor(crsContacts);
                logCursor(crsContactsTest = db.queryContactsTest());
                logCursor(crsPhonesTest = db.queryPhonesTest());
                logCursor(crsEmailsTest = db.queryEmailsTest());
                */

                return data;
            } catch (SQLiteException sqle) {
                Log.e(LOG_TAG, "SQLiteException", sqle);
                return null;
            } finally {
                if(crsContacts != null) {
                    crsContacts.close();
                }
                if(crsPhones != null) {
                    crsPhones.close();
                }
                if(crsEmails != null) {
                    crsEmails.close();
                }
                /* debug
                if(crsContactsTest != null) {
                    crsContactsTest.close();
                }
                if(crsPhonesTest != null) {
                    crsPhonesTest.close();
                }
                if(crsEmailsTest != null) {
                    crsEmailsTest.close();
                }
                */
            }
        }

        @Override
        public void deliverResult(List<? extends Map<String, ?>> data) {
            if(isReset() && data != null) {
                releaseResources(data);
            }
            if(isStarted()) { // deliver now
                super.deliverResult(data);
            }
            // new result delivered, old is not needed no more
            // so time to release old resources
            /* not needed
            if(oldData != null) {
                releaseResources(oldData);
            } */
        }


        @Override
        protected void onStartLoading() {
            forceLoad();
        }

        @Override
        protected void onStopLoading() {
            cancelLoad();
        }

        @Override
        public void onCanceled(List<? extends Map<String, ?>> data) {
            super.onCanceled(data);
            releaseResources(data);
        }

        @Override
        protected void onReset() {
            super.onReset();
            // stop if not stopped
            onStopLoading();
            /* not needed
            if(storedData != null) {
                releaseResources(storedData);
                storedData = null;
            }
            */
        }

        void releaseResources(List<? extends Map<String, ?>> data) {
            // nothing yet
        }
    }

    private static class ContactItemAdapter extends SimpleAdapter {
        private LayoutInflater layInf;
        //private Context context; unused

        ContactItemAdapter(Context context, List<? extends Map<String, ?>> data, int resource,
                                  String[] from, int[] to) {

            super(context, data, resource, from, to);
            //this.context = context; unused
            layInf = (LayoutInflater) context.getSystemService(LAYOUT_INFLATER_SERVICE);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if(convertView == null) {
                convertView = layInf.inflate(R.layout.list_contacts_item, parent, false);
            }
            Map itemData = (Map) getItem(position);

            /* java.lang.IllegalArgumentException: The key must be an application-specific resource id.
            can't just pass number, should use from resource
            https://developer.android.com/reference/android/view/View.html#setTag(java.lang.Object)
            <resources>
                <item type="id" name="id_key_root"/>
            </resources>
             */
            convertView.setTag(R.id.id_key_contact_id, itemData.get(KEY_CONTACT_ID));
            convertView.setTag(R.id.id_key_user_id, itemData.get(KEY_USER_ID));
            ((TextView) convertView.findViewById(R.id.lci_tv_f_name)).setText((String) itemData.get(KEY_FIRST_NAME));
            ((TextView) convertView.findViewById(R.id.lci_tv_l_name)).setText((String) itemData.get(KEY_LAST_NAME));

            LinearLayout listPhones = (LinearLayout) convertView.findViewById(R.id.lci_list_phones);
            LinearLayout listEmails = (LinearLayout) convertView.findViewById(R.id.lci_list_emails);
            listPhones.removeAllViews();
            listEmails.removeAllViews();
            ArrayList<String> alPhones = (ArrayList<String>) itemData.get(KEY_PHONES);
            ArrayList<String> alEmails = (ArrayList<String>) itemData.get(KEY_EMAILS);
            for(String curStr : alPhones) {
                TextView tvNew = (TextView) layInf.inflate(R.layout.list_phones_item, listPhones, false);
                tvNew.setText(curStr);
                listPhones.addView(tvNew);
            }
            for(String curStr : alEmails) {
                TextView tvNew = (TextView) layInf.inflate(R.layout.list_emails_item, listEmails, false);
                tvNew.setText(curStr);
                listEmails.addView(tvNew);
            }

            /* old try
            ((ListView) convertView.findViewById(R.id.lci_lv_phones))
                    .setAdapter(new ArrayAdapter<>(context, R.layout.list_phones_item, (ArrayList<String>) itemData.get(KEY_PHONES)));
            ((ListView) convertView.findViewById(R.id.lci_lv_emails))
                    .setAdapter(new ArrayAdapter<>(context, R.layout.list_emails_item, (ArrayList<String>) itemData.get(KEY_EMAILS)));
                    */

            convertView.setOnClickListener(ocl);
            return convertView;
        }

        private View.OnClickListener ocl = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                view.showContextMenu();
                //reloadList(); not now
            }
        };


    }

    private static class EditTextListAdapter extends ArrayAdapter<String> {
        private LayoutInflater layInfl;
        private List<String> data;
        int itemRes;

        EditTextListAdapter(@NonNull Context context, @LayoutRes int resource, @NonNull List<String> objects) {
            super(context, resource, objects);
            layInfl = LayoutInflater.from(context);
            data = objects;
            itemRes = resource;
        }

        @NonNull @Override
        public View getView(final int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if(convertView == null) {
                convertView = layInfl.inflate(itemRes, parent, false);
            }

            EditText et = (EditText) convertView.findViewById(R.id.dli_et);
            et.setText(getItem(position));
            et.setOnFocusChangeListener(
                    new View.OnFocusChangeListener() {
                        @Override
                        public void onFocusChange(View view, boolean b) {
                            try {
                                data.set(position, "" + ((EditText) view).getText());
                            } catch(ClassCastException | IndexOutOfBoundsException e) {
                                Log.e(LOG_TAG, "EditTextListAdapter OnFocusChangeListener : "
                                        + "Can't save text ! " + e);
                            }
                        }
                    });
            return convertView;
        }
    }

    //-----------------------------------------
    private static class DBAsyncTask extends AsyncTask<Bundle, Void, Boolean> {
        private Context cntx;
        private ContactsDB db;
        private int mode;
        private LoaderManager loaderMgr;
        private LoaderManager.LoaderCallbacks ldrCallbacks;

        DBAsyncTask(Context context, ContactsDB contactsDB, int workingMode,
                    LoaderManager loaderManager, LoaderManager.LoaderCallbacks loaderCallbacks) {
            cntx = context;
            db = contactsDB;
            mode = workingMode;
            loaderMgr = loaderManager;
            ldrCallbacks = loaderCallbacks;
        }

        @Override
        protected Boolean doInBackground(Bundle... args) {
            if(args == null || args.length == 0 || args[0] == null) {
                Log.w(LOG_TAG, "DBAsyncTask empty args");
                return false;
            }

            Bundle arg = args[0];
            long id = arg.getLong(KEY_CONTACT_ID, 0);
            String userID = arg.getString(KEY_USER_ID);
            String firstName = arg.getString(KEY_FIRST_NAME);
            String lastName = arg.getString(KEY_LAST_NAME);
            ArrayList<String> listEmails = arg.getStringArrayList(KEY_EMAILS);
            ArrayList<String> listPhones = arg.getStringArrayList(KEY_PHONES);
            try {
                String res;
                switch (mode) {
                    case MODE_CONTACT_EDIT_NEW:
                        res = "" + db.addContact(userID, firstName, lastName, listEmails, listPhones);
                        break;
                    case MODE_CONTACT_EDIT_UPDATE:
                        res = "" + db.changeContact(id, userID, firstName, lastName, listEmails, listPhones);
                        break;
                    case MODE_CONTACT_DELETE:
                        res = "" + db.deleteContact(id);
                        break;
                    default:
                        Log.w(LOG_TAG, "DBAsyncTask : Unknown mode");
                        return false;
                }
                Log.i(LOG_TAG, "DBAsyncTask : res == " + res);
            } catch (SQLiteException sqle) {
                Log.e(LOG_TAG, "DBAsyncTask SQLiteException", sqle);
                return false;
            }
            return true;
        }

        @Override
        protected void onCancelled(Boolean res) {
            super.onCancelled(res);
            Log.i(LOG_TAG, "DBAsyncTask onCancelled");
        }

        @Override
        protected void onPostExecute(Boolean res) {
            super.onPostExecute(res);
            if(!res) {
                Log.i(LOG_TAG, "DBAsyncTask failed");
                Toast.makeText(cntx, R.string.text_loading_failed, Toast.LENGTH_LONG).show();
            } else {
                //reloadList(); old way
                loaderMgr.restartLoader(QUERY_CONTACTS, null, ldrCallbacks);
            }
        }
    }

    //--------------------------------------------------------------
    // dialog fragments

    public static class DialogGApiErrorFragment extends DialogFragment {
        @NonNull @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            int errCode = getArguments().getInt(KEY_ERR_CODE, -1);
            return GoogleApiAvailability.getInstance().getErrorDialog(getActivity(), errCode,
                    REQUEST_CODE_RESOLVE_ERR);
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            super.onDismiss(dialog);
            if(getActivity() != null) {
                getActivity().finish();
            }
        }
    }

    public static class DialogSortOrderFragment extends DialogFragment {
        private IListItemPrefs lip;

        @NonNull @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            try{
                lip = (IListItemPrefs) getActivity();
            } catch (ClassCastException cce) {
                Log.e(LOG_TAG, "DialogSortOrderFragment : Activity must implement IListItemPrefs", cce);
                return super.onCreateDialog(savedInstanceState);
            }
            return new AlertDialog.Builder(getActivity())
                    .setIcon(android.R.drawable.ic_menu_sort_by_size)
                    .setTitle(R.string.text_sort_order)
                    .setItems(R.array.ar_sort_order,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            lip.setSortOrder(i);
                        }
                    }).create();
        }
    }

    public static class DialogSortDirFragment extends DialogFragment {
        private IListItemPrefs lip;

        @NonNull @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            try{
                lip = (IListItemPrefs) getActivity();
            } catch (ClassCastException cce) {
                Log.e(LOG_TAG, "DialogSortDirFragment : Activity must implement IListItemPrefs", cce);
                return super.onCreateDialog(savedInstanceState);
            }
            return new AlertDialog.Builder(getActivity())
                    .setIcon(android.R.drawable.ic_menu_sort_alphabetically)
                    .setTitle(R.string.text_sort_dir)
                    .setItems(R.array.ar_sort_dir,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            lip.setSortDirection(i);
                        }
                    }).create();
        }
    }

    public static class DialogItemsMaxFragment extends DialogFragment {
        private IListItemPrefs lip;

        @NonNull @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            try{
                lip = (IListItemPrefs) getActivity();
            } catch (ClassCastException cce) {
                Log.e(LOG_TAG, "DialogItemsMaxFragment : Activity must implement IListItemPrefs", cce);
                return super.onCreateDialog(savedInstanceState);
            }
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.text_max_items)
                    .setIcon(android.R.drawable.ic_menu_view)
                    .setItems(R.array.ar_items_max,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            try {
                                lip.setItemsMaxCount(getResources().getStringArray(R.array.ar_items_max)[i]);
                            } catch (IndexOutOfBoundsException iobe) {
                                Log.e(LOG_TAG, "DialogItemsMaxFragment IndexOutOfBoundsException ", iobe);
                            }
                        }
                    }).create();
        }
    }

    public static class DialogContactEditFragment extends DialogFragment {
        private IContactsDBHolder cdbHolder;
        private LoaderManager.LoaderCallbacks ldrCallbacks;
        private View root;
        private EditText etFName;
        private EditText etLName;
        private ListView lvPhones;
        private ListView lvEmails;
        private TextView tvMsg;

        private AlertDialog dialog;

        final private ArrayList<String> listPhones = new ArrayList<>();
        final private ArrayList<String> listEmails = new ArrayList<>();

        private int MODE = -1;

        @SuppressLint("InflateParams") @NonNull @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            try {
                cdbHolder = (IContactsDBHolder) getActivity();
                ldrCallbacks = (LoaderManager.LoaderCallbacks) getActivity();
            } catch(ClassCastException cce) {
                Log.e(LOG_TAG, "DialogContactEditFragment : activity must implement IContactsDBHolder" + cce);
                return super.onCreateDialog(savedInstanceState);
            }

            //LayoutInflater layInfl = getLayoutInflater(null); // wrong!
            // another way
            //LayoutInflater layInfl = (LayoutInflater) getActivity().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            LayoutInflater layInfl = LayoutInflater.from(getActivity());
            root = layInfl.inflate(R.layout.dialog_contact_edit, null);
            etFName = (EditText) root.findViewById(R.id.dce_et_f_name);
            etLName = (EditText) root.findViewById(R.id.dce_et_l_name);
            lvPhones = (ListView) root.findViewById(R.id.dce_lv_phones);
            lvEmails = (ListView) root.findViewById(R.id.dce_lv_emails);
            tvMsg = (TextView) root.findViewById(R.id.dce_tv_msg);

            tvMsg.setVisibility(View.GONE);

            // footer to expand list
            View footerPhone = layInfl.inflate(R.layout.list_item_add, lvPhones, false);
            TextView tvAddPhone = (TextView) footerPhone.findViewById(R.id.lia_tv_add);
            tvAddPhone.append(" " + getString(R.string.text_phone_number));

            /* java.lang.IllegalArgumentException: The key must be an application-specific resource id.
            can't just pass number, should use from resource
            https://developer.android.com/reference/android/view/View.html#setTag(java.lang.Object)
            <resources>
                <item type="id" name="id_key_root"/>
            </resources>
             */
            footerPhone.setTag(R.id.id_key_root, lvPhones);
            footerPhone.setOnClickListener(addFieldOcl);
            lvPhones.addFooterView(footerPhone);

            // footer to expand list
            View footerEmail = layInfl.inflate(R.layout.list_item_add, lvEmails, false);
            TextView tvAddEmail = (TextView) footerEmail.findViewById(R.id.lia_tv_add);
            tvAddEmail.append(" " + getString(R.string.text_email));
            footerEmail.setTag(R.id.id_key_root, lvEmails);
            footerEmail.setOnClickListener(addFieldOcl);
            lvEmails.addFooterView(footerEmail);

            // fill fields..
            Bundle args = getArguments();
            if(args == null) {
                Log.e(LOG_TAG, "DialogContactEditFragment : args == null");
                return super.onCreateDialog(savedInstanceState);
            }
            MODE = args.getInt(KEY_CONTACT_EDIT_MODE, 0);
            switch(MODE) {
                case MODE_CONTACT_EDIT_NEW:
                    break;
                case MODE_CONTACT_EDIT_UPDATE:
                    etFName.setText(args.getString(KEY_FIRST_NAME));
                    etLName.setText(args.getString(KEY_LAST_NAME));
                    ArrayList<String> alPhonesNew = args.getStringArrayList(KEY_PHONES);
                    if(alPhonesNew != null) {
                        listPhones.addAll(alPhonesNew);
                    }
                    ArrayList<String> alEmailsNew = args.getStringArrayList(KEY_EMAILS);
                    if(alEmailsNew != null) {
                        listEmails.addAll(alEmailsNew);
                    }
                    break;
            }
            EditTextListAdapter adapterPhones = new EditTextListAdapter(getActivity(),
                    R.layout.dialog_list_item_phone_et, listPhones);
            EditTextListAdapter adapterEmails = new EditTextListAdapter(getActivity(),
                    R.layout.dialog_list_item_email_et, listEmails);
            lvPhones.setAdapter(adapterPhones);
            lvEmails.setAdapter(adapterEmails);

            dialog = new AlertDialog.Builder(getActivity())
                    .setView(root)
                    .setNegativeButton(android.R.string.cancel, null)
                    // do nothing just to do not close dialog \/
                    .setPositiveButton(android.R.string.ok, null)
                    .create();
            return dialog;
        }

        @Override
        public void onStart() {
            super.onStart();
            // View's listener, not dialog's! \/
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(editOkOcl);
        }

        private View.OnClickListener addFieldOcl = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ListView lv = null;
                try {
                    lv = (ListView) view.getTag(R.id.id_key_root);
                    if(lv == null) {
                        Log.e(LOG_TAG, "addFieldOcl : ListView == null");
                        return;
                    }

                    ArrayAdapter arrayAdapter;
                    if(lv.getAdapter() instanceof WrapperListAdapter) {
                        ListAdapter listAdapter = ((WrapperListAdapter) lv.getAdapter()).getWrappedAdapter();
                        arrayAdapter = (ArrayAdapter) listAdapter;
                    } else {
                        arrayAdapter = (ArrayAdapter) lv.getAdapter();
                    }

                    // arrayAdapter.add(""); //java.lang.UnsupportedOperationException
                    String contDescr = lv.getContentDescription().toString();
                    //Log.d(LOG_TAG, "addFieldOcl : view ContentDescription = " + contDescr); no needed
                    if(getString(R.string.text_phone_number).equals(contDescr)) {
                        listPhones.add("");
                    } else if(getString(R.string.text_email).equals(contDescr)) {
                        listEmails.add("");
                    }
                    arrayAdapter.notifyDataSetChanged();
                } catch (ClassCastException | NullPointerException e) {
                    Log.e(LOG_TAG, "Dialog list add field click error "
                            + (lv == null ? "null" : lv.getContentDescription()), e);
                }
            }
        };

        private View.OnClickListener editOkOcl = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if(etFName == null || etLName == null || lvPhones == null
                        || lvEmails == null || tvMsg == null) {
                    Log.e(LOG_TAG, "DialogContactEditFragment AlertDialog.Builder views == null");
                    return;
                }

                // EditText List data (phones and emails) are saved on focus changed..
                etFName.requestFocus();

                Bundle args = getArguments();

                if(etFName.getText().length() == 0) {
                    tvMsg.setVisibility(View.VISIBLE);
                    tvMsg.setText(getString(R.string.text_first_name) + " "
                            + getString(R.string.text_should_not_empty));
                    return;
                } else {
                    args.putString(KEY_FIRST_NAME, etFName.getText().toString());
                }

                if(etLName.getText().length() == 0) {
                    tvMsg.setVisibility(View.VISIBLE);
                    tvMsg.setText(getString(R.string.text_last_name) + " "
                            + getString(R.string.text_should_not_empty));
                    return;
                } else {
                    args.putString(KEY_LAST_NAME, etLName.getText().toString());
                }

                ArrayList<String> alPhones = new ArrayList<>();
                // getCount() - 1 : because of footer !
                for(int idItem = 0; idItem < lvPhones.getAdapter().getCount() - 1; idItem++) {
                    String field = (String) lvPhones.getAdapter().getItem(idItem);
                    if(TextUtils.isEmpty(field)) {
                        tvMsg.setVisibility(View.VISIBLE);
                        tvMsg.setText(getString(R.string.text_phone_number) + " "
                                + getString(R.string.text_should_not_empty));
                        return;
                    } else {
                        alPhones.add(field);
                    }
                }
                if(alPhones.size() == 0) {
                    tvMsg.setVisibility(View.VISIBLE);
                    tvMsg.setText(getString(R.string.text_phone_number) + " "
                            + getString(R.string.text_should_not_empty));
                    return;
                } else {
                    args.putStringArrayList(KEY_PHONES, alPhones);
                }

                ArrayList<String> alEmails = new ArrayList<>();
                // getCount() - 1 : because of footer !
                for(int idItem = 0; idItem < lvEmails.getAdapter().getCount() - 1; idItem++) {
                    String field = (String) lvEmails.getAdapter().getItem(idItem);
                    if(TextUtils.isEmpty(field)) {
                        tvMsg.setVisibility(View.VISIBLE);
                        tvMsg.setText(getString(R.string.text_email) + " "
                                + getString(R.string.text_should_not_empty));
                        return;
                    } else {
                        alEmails.add(field);
                    }
                }
                if(alEmails.size() == 0) {
                    tvMsg.setVisibility(View.VISIBLE);
                    tvMsg.setText(getString(R.string.text_email) + " "
                            + getString(R.string.text_should_not_empty));
                    return;
                } else {
                    args.putStringArrayList(KEY_EMAILS, alEmails);
                }

                ContactsDB db = cdbHolder.getContactsDB();
                if(db == null) {
                    Log.w(LOG_TAG, "DialogContactEditFragment : ContactsDB == null");
                } else {
                    DBAsyncTask dbat = new DBAsyncTask(getActivity(), db, MODE,
                            getActivity().getSupportLoaderManager(), ldrCallbacks);
                    dbat.executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, args);
                }
                dialog.dismiss(); // because it's custom listener..
            }
        };
    }
}
