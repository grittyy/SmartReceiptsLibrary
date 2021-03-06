package co.smartreceipts.android.model.impl.columns.receipts;

import android.support.annotation.NonNull;

import co.smartreceipts.android.model.Receipt;
import co.smartreceipts.android.model.impl.columns.AbstractColumnImpl;

/**
 * Provides a column that returns the category code for a particular receipt
 */
public final class ReceiptExtra1Column extends AbstractColumnImpl<Receipt> {

    public ReceiptExtra1Column(int id, @NonNull String name) {
        super(id, name);
    }

    @Override
    public String getValue(@NonNull Receipt receipt) {
        return receipt.getExtraEditText1();
    }
}
