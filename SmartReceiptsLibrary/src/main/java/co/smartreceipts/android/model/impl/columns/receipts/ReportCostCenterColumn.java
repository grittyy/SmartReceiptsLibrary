package co.smartreceipts.android.model.impl.columns.receipts;

import android.support.annotation.NonNull;

import co.smartreceipts.android.model.Receipt;
import co.smartreceipts.android.model.impl.columns.AbstractColumnImpl;

/**
 * Provides a column that returns the category code for a particular receipt
 */
public final class ReportCostCenterColumn extends AbstractColumnImpl<Receipt> {

    public ReportCostCenterColumn(int id, @NonNull String name) {
        super(id, name);
    }

    @Override
    public String getValue(@NonNull Receipt receipt) {
        return receipt.getTrip().getCostCenter();
    }
}
