package co.smartreceipts.android.persistence.database.tables;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.List;

import co.smartreceipts.android.model.Column;
import co.smartreceipts.android.model.ColumnDefinitions;
import co.smartreceipts.android.model.Receipt;
import co.smartreceipts.android.persistence.database.tables.adapters.ColumnDatabaseAdapter;
import co.smartreceipts.android.persistence.database.tables.keys.ColumnPrimaryKey;
import co.smartreceipts.android.utils.ListUtils;

/**
 * Since our CSV and PDF tables share almost all of the same logic, this class purely acts as a wrapper around
 * each to centralize where all logic is managed
 */
abstract class AbstractColumnTable extends AbstractSqlTable<Column<Receipt>, Integer> {

    private static final String TAG = AbstractColumnTable.class.getSimpleName();

    private final int mTableExistsSinceDatabaseVersion;
    private final ColumnDefinitions<Receipt> mReceiptColumnDefinitions;
    private final String mIdColumnName;
    private final String mTypeColumnName;

    public AbstractColumnTable(@NonNull SQLiteOpenHelper sqLiteOpenHelper, @NonNull String tableName, int tableExistsSinceDatabaseVersion,
                               @NonNull ColumnDefinitions<Receipt> receiptColumnDefinitions, @NonNull String idColumnName, @NonNull String typeColumnName) {
        super(sqLiteOpenHelper, tableName, new ColumnDatabaseAdapter(receiptColumnDefinitions, idColumnName, typeColumnName), new ColumnPrimaryKey(idColumnName));
        mTableExistsSinceDatabaseVersion = tableExistsSinceDatabaseVersion;
        mReceiptColumnDefinitions = Preconditions.checkNotNull(receiptColumnDefinitions);
        mIdColumnName = Preconditions.checkNotNull(idColumnName);
        mTypeColumnName = Preconditions.checkNotNull(typeColumnName);
    }

    @Override
    public synchronized void onCreate(@NonNull SQLiteDatabase db, @NonNull TableDefaultsCustomizer customizer) {
        super.onCreate(db, customizer);
        this.createColumnsTable(db, customizer);
    }

    @Override
    public synchronized void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion, @NonNull TableDefaultsCustomizer customizer) {
        super.onUpgrade(db, oldVersion, newVersion, customizer);
        if (oldVersion <= mTableExistsSinceDatabaseVersion) {
            this.createColumnsTable(db, customizer);
        }
    }

    private void createColumnsTable(@NonNull SQLiteDatabase db, @NonNull TableDefaultsCustomizer customizer) {
        final String columnsTable = "CREATE TABLE " + getTableName() + " (" +
                                    mIdColumnName + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                    mTypeColumnName + " TEXT" + ");";
        Log.d(TAG, columnsTable);

        db.execSQL(columnsTable);
        insertDefaults(customizer);
    }

    /**
     * Inserts the default column as defined by {@link ColumnDefinitions#getDefaultInsertColumn()}
     *
     * @return the inserted {@link Column} or {@code null} if the insert failed
     */
    @Nullable
    public synchronized Column<Receipt> insertDefaultColumn() {
        return insert(mReceiptColumnDefinitions.getDefaultInsertColumn());
    }

    /**
     * Attempts to delete the last column in the list
     *
     * @return {@code true} if it could be delete. {@code false} otherwise (e.g. there are no more columns)
     */
    public synchronized boolean deleteLast() {
        final List<Column<Receipt>> columns = new ArrayList<>(get());
        final Column<Receipt> lastColumn = ListUtils.removeLast(columns);
        if (lastColumn != null) {
            return delete(lastColumn);
        } else {
            return false;
        }
    }

    /**
     * Passes alongs a call to insert our "table" defaults to the appropriate sub implementation
     *
     * @param customizer the {@link co.smartreceipts.android.persistence.DatabaseHelper.TableDefaultsCustomizer} implementation
     */
    protected abstract void insertDefaults(@NonNull TableDefaultsCustomizer customizer);

}