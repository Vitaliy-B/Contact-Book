package bv.dev.sannacode.contactbook;

/**
 * interface for dialog fragments with contacts list appearance settings
 */

interface IListItemPrefs {
    void setSortOrder(int order);
    void setSortDirection(int dir);
    void setItemsMaxCount(String max);
}
