package bv.dev.sannacode.contactbook;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;

/**
 * Wrapper class for database.
 * Caller should catch SQLiteException
 */
class ContactsDB {
    private static final String LOG_TAG = "bv_log";
    private static final String DB_NAME = "ContactsDB";
    private static final int DB_VERSION = 1;

    //------------------------------
    // TABLES
    // package access
    static final class TabContacts {
        static final String TAB_NAME = "contacts";
        static final String COL_ID = "_id";
        static final String COL_USER_ID = "user_id";
        static final String COL_NAME_FIRST = "name_first";
        static final String COL_NAME_LAST = "name_last";

        private static final String QUERY_CREATE_TAB = "CREATE TABLE " + TAB_NAME
                + "( " + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + COL_USER_ID + " TEXT NOT NULL, "
                + COL_NAME_FIRST + " TEXT, "
                + COL_NAME_LAST + " TEXT "
                + ");";
        private static final String QUERY_DROP_TAB_IF_EXISTS = "DROP TABLE IF EXISTS " + TAB_NAME + ";";
    }

    static final class TabEmails {
        static final String TAB_NAME = "emails";
        static final String COL_ID = "_id";
        static final String COL_CONTACT_ID = "contact_id";
        static final String COL_EMAIL = "email";

        private static final String QUERY_CREATE_TAB = "CREATE TABLE " + TAB_NAME
                + "( " + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + COL_EMAIL + " TEXT, "
                + COL_CONTACT_ID + " INTEGER NOT NULL, "
                + "FOREIGN KEY (" + COL_CONTACT_ID + ") REFERENCES "
                + TabContacts.TAB_NAME + " (" + TabContacts.COL_ID + "));";

        private static final String QUERY_DROP_TAB_IF_EXISTS = "DROP TABLE IF EXISTS " + TAB_NAME + ";";
    }

    static final class TabPhones {
        static final String TAB_NAME = "phones";
        static final String COL_ID = "_id";
        static final String COL_CONTACT_ID = "contact_id";
        static final String COL_PHONE = "phone";

        private static final String QUERY_CREATE_TAB = "CREATE TABLE " + TAB_NAME
                + "( " + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, "
                + COL_PHONE + " TEXT, "
                + COL_CONTACT_ID + " INTEGER NOT NULL, "
                + "FOREIGN KEY (" + COL_CONTACT_ID + ") REFERENCES "
                + TabContacts.TAB_NAME + " (" + TabContacts.COL_ID + "));";

        private static final String QUERY_DROP_TAB_IF_EXISTS = "DROP TABLE IF EXISTS " + TAB_NAME + ";";
    }
    //------------------------------

    private ContactsDBHelper contactsDBHelper;

    ContactsDB(Context context) {
        contactsDBHelper = new ContactsDBHelper(context, DB_NAME, null, DB_VERSION);
    }

    void clearTables() throws SQLiteException {
        SQLiteDatabase db = contactsDBHelper.getWritableDatabase();
        db.execSQL(TabContacts.QUERY_DROP_TAB_IF_EXISTS);
        db.execSQL(TabEmails.QUERY_DROP_TAB_IF_EXISTS);
        db.execSQL(TabPhones.QUERY_DROP_TAB_IF_EXISTS);

        db.execSQL(TabContacts.QUERY_CREATE_TAB);
        db.execSQL(TabEmails.QUERY_CREATE_TAB);
        db.execSQL(TabPhones.QUERY_CREATE_TAB);
    }

    boolean addContact(String userID, String firstName, String lastName,
                       ArrayList<String> listEmails, ArrayList<String> listPhones) throws SQLiteException {

        SQLiteDatabase db = contactsDBHelper.getWritableDatabase();
        try {
            db.beginTransaction();
            long contactID = insertContact(db, userID, firstName, lastName);
            if(contactID == -1) {
                return false;
            }
            for(String email : listEmails) {
                if(insertEmail(db, contactID, email) == -1) {
                    return false;
                }
            }
            for(String phone : listPhones) {
                if(insertPhone(db, contactID, phone) == -1) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    int deleteContact(long id) throws SQLiteException {
        return contactsDBHelper.getWritableDatabase().delete(TabContacts.TAB_NAME, TabContacts.COL_ID + " = " + id, null);
    }

    boolean changeContact(long contactID, String userID, String firstName, String lastName,
                          ArrayList<String> listEmails, ArrayList<String> listPhones) throws SQLiteException {

        SQLiteDatabase db = contactsDBHelper.getWritableDatabase();
        try {
            db.beginTransaction();

            deleteContactEmails(db, contactID);
            deleteContactPhones(db, contactID);
            if(updateContact(db, contactID, userID, firstName, lastName) < 1) {
                return false;
            }
            for(String email : listEmails) {
                if(insertEmail(db, contactID, email) == -1) {
                    return false;
                }
            }
            for(String phone : listPhones) {
                if(insertPhone(db, contactID, phone) == -1) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    enum ContactsSortOrder {
        ByDate, ByName
    }

    enum ContactsSortDirection {
        ASC, DESC
    }

    Cursor queryContactsTest() throws SQLiteException {
        SQLiteDatabase db = contactsDBHelper.getReadableDatabase();
        return db.query(TabContacts.TAB_NAME, null, null, null, null, null, null);
    }

    Cursor queryPhonesTest() throws SQLiteException {
        SQLiteDatabase db = contactsDBHelper.getReadableDatabase();
        return db.query(TabPhones.TAB_NAME, null, null, null, null, null, null);
    }

    Cursor queryEmailsTest() throws SQLiteException {
        SQLiteDatabase db = contactsDBHelper.getReadableDatabase();
        return db.query(TabEmails.TAB_NAME, null, null, null, null, null, null);
    }

    Cursor queryContacts(String userID, ContactsSortOrder sortOrder,
                         ContactsSortDirection sortDir, String limit) throws SQLiteException {

        SQLiteDatabase db = contactsDBHelper.getReadableDatabase();
        String orderBy = null;
        switch(sortOrder) {
            case ByDate:
                orderBy = TabContacts.COL_ID + " " + sortDir;
                break;
            case ByName:
                orderBy = TabContacts.COL_NAME_FIRST + " " + sortDir + ", "
                        + TabContacts.COL_NAME_LAST + " " + sortDir;
                break;
        }
        return db.query(TabContacts.TAB_NAME, null, TabContacts.COL_USER_ID + " = ?",
                new String[] {userID}, null, null, orderBy, limit);
    }

    Cursor queryPhones(long contactID) throws SQLiteException  {
        SQLiteDatabase db = contactsDBHelper.getReadableDatabase();
        return db.query(TabPhones.TAB_NAME, null, TabPhones.COL_CONTACT_ID + " = " + contactID, null, null, null, null);
    }

    Cursor queryEmails(long contactID) throws SQLiteException  {
        SQLiteDatabase db = contactsDBHelper.getReadableDatabase();
        return db.query(TabEmails.TAB_NAME, null, TabEmails.COL_CONTACT_ID + " = " + contactID, null, null, null, null);
    }

    //-----------------------------------
    // private

    // Returns long - the row ID of the newly inserted row, or -1 if an error occurred
    private long insertContact(SQLiteDatabase db, String userID, String firstName, String lastName)
            throws SQLiteException {
        ContentValues cv = new ContentValues(3);
        cv.put(TabContacts.COL_USER_ID, userID);
        cv.put(TabContacts.COL_NAME_FIRST, firstName);
        cv.put(TabContacts.COL_NAME_LAST, lastName);
        Log.d(LOG_TAG, "ContactsDB.insertContact : ContentValues == " + cv);
        return db.insert(TabContacts.TAB_NAME, null, cv);
    }

    // Returns long - the row ID of the newly inserted row, or -1 if an error occurred
    private long insertEmail(SQLiteDatabase db, long contactID, String email)
            throws SQLiteException {
        ContentValues cv = new ContentValues(2);
        cv.put(TabEmails.COL_CONTACT_ID, contactID);
        cv.put(TabEmails.COL_EMAIL, email);
        return db.insert(TabEmails.TAB_NAME, null, cv);
    }

    // Returns long - the row ID of the newly inserted row, or -1 if an error occurred
    private long insertPhone(SQLiteDatabase db, long contactID, String phone)
            throws SQLiteException {
        ContentValues cv = new ContentValues(2);
        cv.put(TabPhones.COL_CONTACT_ID, contactID);
        cv.put(TabPhones.COL_PHONE, phone);
        return db.insert(TabPhones.TAB_NAME, null, cv);
    }

    private int updateContact(SQLiteDatabase db, long id, String userID, String firstName, String lastName)
            throws SQLiteException {
        ContentValues cv = new ContentValues(3);
        cv.put(TabContacts.COL_USER_ID, userID);
        cv.put(TabContacts.COL_NAME_FIRST, firstName);
        cv.put(TabContacts.COL_NAME_LAST, lastName);
        return db.update(TabContacts.TAB_NAME, cv, TabContacts.COL_ID + " = " + id, null);
    }

    private int deleteContactPhones(SQLiteDatabase db, long contactID) throws SQLiteException {
        return db.delete(TabPhones.TAB_NAME, TabPhones.COL_CONTACT_ID + " = " + contactID, null);
    }

    private int deleteContactEmails(SQLiteDatabase db, long contactID) throws SQLiteException {
        return db.delete(TabEmails.TAB_NAME, TabEmails.COL_CONTACT_ID + " = " + contactID, null);
    }

    //------------------------------
    private static class ContactsDBHelper extends SQLiteOpenHelper {
        ContactsDBHelper(Context context, String name, SQLiteDatabase.CursorFactory factory, int version) {
            super(context, name, factory, version);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(TabContacts.QUERY_CREATE_TAB);
            db.execSQL(TabEmails.QUERY_CREATE_TAB);
            db.execSQL(TabPhones.QUERY_CREATE_TAB);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // no upgrade supported yet
        }
    }
}

