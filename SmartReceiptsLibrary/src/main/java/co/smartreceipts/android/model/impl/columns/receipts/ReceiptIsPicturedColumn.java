package co.smartreceipts.android.model.impl.columns.receipts;

import android.content.Context;
import android.support.annotation.NonNull;

import co.smartreceipts.android.R;
import co.smartreceipts.android.model.Receipt;
import co.smartreceipts.android.model.impl.columns.AbstractColumnImpl;

/**
 * Provides a column that returns the category code for a particular receipt
 */
public final class ReceiptIsPicturedColumn extends AbstractColumnImpl<Receipt> {

    private final Context mContext;

    public ReceiptIsPicturedColumn(int id, @NonNull String name, @NonNull Context context) {
        super(id, name);
        mContext = context;
    }

    @Override
    public String getValue(@NonNull Receipt receipt) {
        if (receipt.hasImage()) {
            return mContext.getString(R.string.yes);
        } else if (receipt.hasPDF()) {
            return mContext.getString(R.string.yes_as_pdf);
        } else {
            return mContext.getString(R.string.no);
        }
    }
}
