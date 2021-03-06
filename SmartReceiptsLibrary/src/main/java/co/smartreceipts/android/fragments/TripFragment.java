package co.smartreceipts.android.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.SQLException;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.text.method.TextKeyListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Arrays;

import co.smartreceipts.android.R;
import co.smartreceipts.android.activities.Attachable;
import co.smartreceipts.android.activities.DefaultFragmentProvider;
import co.smartreceipts.android.activities.NavigationHandler;
import co.smartreceipts.android.activities.SmartReceiptsActivity;
import co.smartreceipts.android.adapters.TripCardAdapter;
import co.smartreceipts.android.date.DateEditText;
import co.smartreceipts.android.model.Attachment;
import co.smartreceipts.android.model.Trip;
import co.smartreceipts.android.persistence.DatabaseHelper;
import co.smartreceipts.android.persistence.LastTripController;
import co.smartreceipts.android.persistence.PersistenceManager;
import co.smartreceipts.android.utils.FileUtils;
import co.smartreceipts.android.workers.EmailAssistant;
import co.smartreceipts.android.workers.ImportTask;
import wb.android.async.BooleanTaskCompleteDelegate;
import wb.android.autocomplete.AutoCompleteAdapter;
import wb.android.dialog.BetterDialogBuilder;
import wb.android.dialog.LongLivedOnClickListener;

public class TripFragment extends WBListFragment implements BooleanTaskCompleteDelegate, DatabaseHelper.TripRowListener, AdapterView.OnItemLongClickListener {

    public static final String TAG = "TripFragment";

    private static final String KEY_BOOL_FIRST_PASS = "key_first_pass";

    private NavigationHandler mNavigationHandler;
    private TripCardAdapter mAdapter;
    private AutoCompleteAdapter mNameAutoCompleteAdapter, mCostCenterAutoCompleteAdapter;
    private boolean mIsFirstPass; // Tracks that this is the first time we're using this
    private Attachable mAttachable;
    private ProgressBar mProgressDialog;
    private TextView mNoDataAlert;

    public static TripFragment newInstance() {
        return new TripFragment();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        Log.d(TAG, "onAttach");
        if (activity instanceof Attachable) {
            mAttachable = (Attachable) activity;
        } else {
            throw new IllegalArgumentException("The TripFragment's Activity must extend the Navigable and Attachable interfaces");
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        mNavigationHandler = new NavigationHandler(getActivity(), getFragmentManager(), new DefaultFragmentProvider());
        mIsFirstPass = savedInstanceState == null || savedInstanceState.getBoolean(KEY_BOOL_FIRST_PASS, true);
        mAdapter = new TripCardAdapter(getActivity(), getPersistenceManager().getPreferences());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        Log.d(TAG, "onCreateView");
        final View rootView = inflater.inflate(getLayoutId(), container, false);
        mProgressDialog = (ProgressBar) rootView.findViewById(R.id.progress);
        mNoDataAlert = (TextView) rootView.findViewById(R.id.no_data);
        rootView.findViewById(R.id.trip_action_new).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                tripMenu(null);
            }
        });
        return rootView;
    }


    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        setListAdapter(mAdapter); // Set this here to ensure this has been laid out already
        getListView().setOnItemLongClickListener(this);
        final Toolbar toolbar = (Toolbar) getActivity().findViewById(R.id.toolbar);
        if (toolbar != null) {
            ((AppCompatActivity) getActivity()).setSupportActionBar(toolbar);
        }
    }

    public int getLayoutId() {
        return R.layout.trip_fragment_layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
        getPersistenceManager().getDatabase().registerTripRowListener(this);
        getPersistenceManager().getDatabase().getTripsParallel();
        getActivity().setTitle(getFlexString(R.string.sr_app_name));
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            getSupportActionBar().setSubtitle(null);
        }
        // Handles SMR imports
        final Attachment attachment = mAttachable.getAttachment();
        if (attachment != null && attachment.isValid() && attachment.isSMR() && attachment.isActionView()) {
            performImport(attachment.getUri());
        }
    }

    @Override
    public void onPause() {
        Log.d(TAG, "onPause");
        if (mNameAutoCompleteAdapter != null) {
            mNameAutoCompleteAdapter.onPause();
        }
        if (mCostCenterAutoCompleteAdapter != null) {
            mCostCenterAutoCompleteAdapter.onPause();
        }
        getPersistenceManager().getDatabase().unregisterTripRowListener(this);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        Log.d(TAG, "onSaveInstanceState");
        outState.putBoolean(KEY_BOOL_FIRST_PASS, mIsFirstPass);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        Log.d(TAG, "Result Code: " + resultCode);
        Log.d(TAG, "Request Code: " + requestCode);

        if (resultCode == Activity.RESULT_OK) { // -1
            if (requestCode == ImportTask.TASK_ID) {
                if (data != null) {
                    performImport(data.getData());
                }
            }
        } else {
            Log.e(TAG, "Unrecgonized Result Code: " + resultCode);
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    public final void tripMenu(final Trip trip) {
        final PersistenceManager persistenceManager = getPersistenceManager();
        if (!persistenceManager.getStorageManager().isExternal()) {
            Toast.makeText(getActivity(), getFlexString(R.string.SD_ERROR), Toast.LENGTH_LONG).show();
            return;
        }

        final boolean newTrip = (trip == null);

        final View scrollView = getFlex().getView(getActivity(), R.layout.dialog_tripmenu);
        final AutoCompleteTextView nameBox = (AutoCompleteTextView) getFlex().getSubView(getActivity(), scrollView, R.id.dialog_tripmenu_name);
        final DateEditText startBox = (DateEditText) getFlex().getSubView(getActivity(), scrollView, R.id.dialog_tripmenu_start);
        final DateEditText endBox = (DateEditText) getFlex().getSubView(getActivity(), scrollView, R.id.dialog_tripmenu_end);
        final Spinner currencySpinner = (Spinner) getFlex().getSubView(getActivity(), scrollView, R.id.dialog_tripmenu_currency);
        final EditText commentBox = (EditText) getFlex().getSubView(getActivity(), scrollView, R.id.dialog_tripmenu_comment);
        final AutoCompleteTextView costCenterBox = (AutoCompleteTextView) scrollView.findViewById(R.id.dialog_tripmenu_cost_center);
        costCenterBox.setVisibility(getPersistenceManager().getPreferences().getIncludeCostCenter() ? View.VISIBLE : View.GONE);

        final ArrayAdapter<CharSequence> currenices = new ArrayAdapter<CharSequence>(getActivity(), android.R.layout.simple_spinner_item, getPersistenceManager().getDatabase().getCurrenciesList());
        currenices.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        currencySpinner.setAdapter(currenices);

        // Show default dictionary with auto-complete
        TextKeyListener input = TextKeyListener.getInstance(true, TextKeyListener.Capitalize.SENTENCES);
        nameBox.setKeyListener(input);

        // Fill Out Fields
        if (newTrip) {
            if (persistenceManager.getPreferences().enableAutoCompleteSuggestions()) {
                final DatabaseHelper db = getPersistenceManager().getDatabase();
                if (mNameAutoCompleteAdapter == null) {
                    mNameAutoCompleteAdapter = AutoCompleteAdapter.getInstance(getActivity(), DatabaseHelper.TAG_TRIPS_NAME, db);
                } else {
                    mNameAutoCompleteAdapter.reset();
                }
                if (mCostCenterAutoCompleteAdapter == null) {
                    mCostCenterAutoCompleteAdapter = AutoCompleteAdapter.getInstance(getActivity(), DatabaseHelper.TAG_TRIPS_COST_CENTER, db);
                } else {
                    mCostCenterAutoCompleteAdapter.reset();
                }
                nameBox.setAdapter(mNameAutoCompleteAdapter);
                costCenterBox.setAdapter(mCostCenterAutoCompleteAdapter);
            }
            startBox.setFocusableInTouchMode(false);
            startBox.setOnClickListener(getDateManager().getDurationDateEditTextListener(endBox));
            int idx = currenices.getPosition(getPersistenceManager().getPreferences().getDefaultCurreny());
            if (idx > 0) {
                currencySpinner.setSelection(idx);
            }
        } else {
            if (trip.getDirectory() != null) {
                nameBox.setText(trip.getName());
            }
            if (trip.getStartDate() != null) {
                startBox.setText(trip.getFormattedStartDate(getActivity(), getPersistenceManager().getPreferences().getDateSeparator()));
                startBox.date = trip.getStartDate();
            }
            if (trip.getEndDate() != null) {
                endBox.setText(trip.getFormattedEndDate(getActivity(), getPersistenceManager().getPreferences().getDateSeparator()));
                endBox.date = trip.getEndDate();
            }
            if (!TextUtils.isEmpty(trip.getComment())) {
                commentBox.setText(trip.getComment());
            }
            int idx = currenices.getPosition(trip.getDefaultCurrencyCode());
            if (idx > 0) {
                currencySpinner.setSelection(idx);
            }
            startBox.setFocusableInTouchMode(false);
            startBox.setOnClickListener(getDateManager().getDateEditTextListener());
            if (!TextUtils.isEmpty(trip.getCostCenter())) {
                costCenterBox.setText(trip.getCostCenter());
            }

            currencySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    final String newCurrencyCode = currenices.getItem(position).toString();
                    if (!trip.getDefaultCurrencyCode().equals(newCurrencyCode)) {
                        Toast.makeText(view.getContext(), R.string.toast_warning_reset_exchange_rate, Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // Intentional no-op
                }
            });
        }
        endBox.setFocusableInTouchMode(false);
        endBox.setOnClickListener(getDateManager().getDateEditTextListener());
        nameBox.setSelection(nameBox.getText().length()); // Put the cursor at the end

        // Show the DialogController
        final BetterDialogBuilder builder = new BetterDialogBuilder(getActivity());
        builder.setTitle((newTrip) ? getFlexString(R.string.DIALOG_TRIPMENU_TITLE_NEW) : getFlexString(R.string.DIALOG_TRIPMENU_TITLE_EDIT)).setCancelable(true).setView(scrollView).setLongLivedPositiveButton((newTrip) ? getFlexString(R.string.DIALOG_TRIPMENU_POSITIVE_BUTTON_CREATE) : getFlexString(R.string.DIALOG_TRIPMENU_POSITIVE_BUTTON_UPDATE), new LongLivedOnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int whichButton) {
                String name = nameBox.getText().toString().trim();
                final String startDate = startBox.getText().toString();
                final String endDate = endBox.getText().toString();
                final String defaultCurrencyCode = currencySpinner.getSelectedItem().toString();
                final String comment = commentBox.getText().toString();
                final String costCenter = costCenterBox.getText().toString();
                // Error Checking
                if (name.length() == 0 || startDate.length() == 0 || endDate.length() == 0) {
                    Toast.makeText(getActivity(), getFlexString(R.string.DIALOG_TRIPMENU_TOAST_MISSING_FIELD), Toast.LENGTH_LONG).show();
                    return;
                }
                if (startBox.date == null || endBox.date == null) {
                    Toast.makeText(getActivity(), getFlexString(R.string.CALENDAR_TAB_ERROR), Toast.LENGTH_LONG).show();
                    return;
                }
                if (startBox.date.getTime() > endBox.date.getTime()) {
                    Toast.makeText(getActivity(), getFlexString(R.string.DURATION_ERROR), Toast.LENGTH_LONG).show();
                    return;
                }
                if (name.startsWith(" ")) {
                    Toast.makeText(getActivity(), getFlexString(R.string.SPACE_ERROR), Toast.LENGTH_LONG).show();
                    return;
                }
                if (FileUtils.filenameContainsIllegalCharacter(name)) {
                    Toast.makeText(getActivity(), getFlexString(R.string.ILLEGAL_CHAR_ERROR), Toast.LENGTH_LONG).show();
                    return;
                }

                if (newTrip) { // Insert
                    getWorkerManager().getLogger().logEvent(TripFragment.this, "New_Trip");
                    File dir = persistenceManager.getStorageManager().mkdir(name);
                    if (dir != null) {
                        persistenceManager.getDatabase().insertTripParallel(dir, startBox.date, endBox.date, comment, costCenter, defaultCurrencyCode);
                    } else {
                        Toast.makeText(getActivity(), getFlexString(R.string.SD_ERROR), Toast.LENGTH_LONG).show();
                    }
                    dialog.cancel();
                } else { // Update
                    getWorkerManager().getLogger().logEvent(TripFragment.this, "Update_Trip");
                    final File dir = persistenceManager.getStorageManager().rename(trip.getDirectory(), name);
                    if (dir == trip.getDirectory()) {
                        Toast.makeText(getActivity(), getFlexString(R.string.SD_ERROR), Toast.LENGTH_LONG).show();
                        return;
                    }
                    persistenceManager.getDatabase().updateTripParallel(trip, dir, (startBox.date != null) ? startBox.date : trip.getStartDate(), (endBox.date != null) ? endBox.date : trip.getStartDate(), comment, costCenter, defaultCurrencyCode);
                    dialog.cancel();
                }
            }
        }).setNegativeButton(getFlexString(R.string.DIALOG_TRIPMENU_NEGATIVE_BUTTON), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String name = nameBox.getText().toString().trim();
                if (name != null && name.equalsIgnoreCase("_import_")) {
                    File smr = persistenceManager.getStorageManager().getFile("SmartReceipts.smr");
                    if (smr != null && smr.exists()) {
                        final Uri uri = Uri.fromFile(smr);
                        performImport(uri);
                    }
                }
                dialog.cancel();
            }
        }).show();
    }

    public final boolean editTrip(final Trip trip) {
        final BetterDialogBuilder builder = new BetterDialogBuilder(getActivity());
        final String[] editTripItems = getFlex().getStringArray(getActivity(), R.array.EDIT_TRIP_ITEMS);
        builder.setTitle(trip.getName()).setCancelable(true).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        }).setItems(editTripItems, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int item) {
                final String selection = editTripItems[item].toString();
                if (selection == editTripItems[0]) { // Email Trip
                    TripFragment.this.emailTrip(trip);
                } else if (selection == editTripItems[1]) {
                    TripFragment.this.tripMenu(trip);
                } else if (selection == editTripItems[2]) {
                    TripFragment.this.deleteTrip(trip);
                }
                dialog.cancel();
            }
        }).show();
        return true;
    }

    public void emailTrip(Trip trip) {
        EmailAssistant.email(getSmartReceiptsApplication(), getActivity(), trip);
    }

    public final void deleteTrip(final Trip trip) {
        final BetterDialogBuilder builder = new BetterDialogBuilder(getActivity());
        builder.setTitle(getFlexString(R.string.DIALOG_TRIP_DELETE_POSITIVE_BUTTON_TITLE_START) + " " + trip.getName() + getFlexString(R.string.DIALOG_TRIP_DELETE_POSITIVE_BUTTON_TITLE_END)).setCancelable(true).setPositiveButton(getFlexString(R.string.DIALOG_TRIP_DELETE_POSITIVE_BUTTON), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                getPersistenceManager().getDatabase().deleteTripParallel(trip);
            }
        }).setNegativeButton(getFlexString(R.string.DIALOG_CANCEL), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        }).show();
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        viewReceipts(mAdapter.getItem(position));
        // v.setSelected(true);
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> a, View v, int position, long id) {
        editTrip(mAdapter.getItem(position));
        return true;
    }

    @Override
    public void onTripRowsQuerySuccess(Trip[] trips) {
        if (isResumed()) {
            mProgressDialog.setVisibility(View.GONE);
            getListView().setVisibility(View.VISIBLE);
            if (trips == null || trips.length == 0) {
                mNoDataAlert.setVisibility(View.VISIBLE);
            } else {
                mNoDataAlert.setVisibility(View.INVISIBLE);
            }
            mAdapter.notifyDataSetChanged(Arrays.asList(trips));
            if (mIsFirstPass) { // Pre-Cache the receipts for the top two trips
                mIsFirstPass = false;
                if (trips.length > 0) {
                    getPersistenceManager().getDatabase().getReceiptsParallel(trips[0], true);
                }
                if (trips.length > 1) {
                    getPersistenceManager().getDatabase().getReceiptsParallel(trips[1], true);
                }
                if (trips.length > 0) {
                    // If we have trips, open up whatever one was last
                    final LastTripController lastTripController = new LastTripController(getActivity(), getPersistenceManager().getDatabase());
                    // TODO: Move this request off the UI thread
                    final Trip lastTrip = lastTripController.getLastTrip();
                    if (lastTrip != null) {
                        viewReceipts(lastTrip);
                    }
                }
            }
        }
    }

    @Override
    public void onTripRowInsertSuccess(Trip trip) {
        if (isResumed()) {
            viewReceipts(trip);
        }
        getPersistenceManager().getDatabase().getTripsParallel();
    }

    @Override
    public void onTripRowInsertFailure(SQLException ex, File directory) {
        if (ex != null) {
            if (isAdded()) {
                Toast.makeText(getActivity(), R.string.toast_error_trip_exists, Toast.LENGTH_LONG).show();
            }
        } else {
            if (isAdded()) {
                Toast.makeText(getActivity(), getFlexString(R.string.DB_ERROR), Toast.LENGTH_LONG).show();
            }
            getPersistenceManager().getStorageManager().delete(directory);
        }

    }

    @Override
    public void onTripRowUpdateSuccess(Trip trip) {
        getPersistenceManager().getDatabase().getTripsParallel();
        if (isResumed()) {
            viewReceipts(trip);
        }
    }

    @Override
    public void onTripRowUpdateFailure(Trip newTrip, Trip oldTrip, File directory) {
        getPersistenceManager().getStorageManager().rename(directory, oldTrip.getName());
        if (isAdded()) {
            Toast.makeText(getActivity(), getFlexString(R.string.DB_ERROR), Toast.LENGTH_LONG).show();
        }
        if (isResumed()) {
            viewReceipts(newTrip);
        }
    }

    @Override
    public void onTripDeleteSuccess(Trip oldTrip) {
        if (oldTrip != null) {
            if (!getPersistenceManager().getStorageManager().deleteRecursively(oldTrip.getDirectory())) {
                if (isAdded()) {
                    Toast.makeText(getActivity(), getFlexString(R.string.SD_ERROR), Toast.LENGTH_LONG).show();
                }
            }
        }
        if (isAdded()) {
            final Fragment detailsFragment = getFragmentManager().findFragmentByTag(ReceiptsFragment.TAG);
            if (detailsFragment != null) {
                getFragmentManager().beginTransaction().remove(detailsFragment).commit();
                final ActionBar actionBar = getSupportActionBar();
                if (actionBar != null) {
                    actionBar.setTitle(getFlexString(R.string.sr_app_name));
                }
            }
        }
        getPersistenceManager().getDatabase().getTripsParallel();
    }

    @Override
    public void onTripDeleteFailure() {
        if (isAdded()) {
            Toast.makeText(getActivity(), getFlexString(R.string.DB_ERROR), Toast.LENGTH_LONG).show();
        }
    }

    private void viewReceipts(Trip trip) {
        mNavigationHandler.navigateToReportInfoFragment(trip);
    }

    private void performImport(final Uri uri) {
        final CheckBox overwrite = new CheckBox(getActivity());
        overwrite.setText(" Overwrite Existing Data?");
        final BetterDialogBuilder builder = new BetterDialogBuilder(getActivity());
        builder.setTitle(R.string.import_string).setView(overwrite).setCancelable(true).setPositiveButton(R.string.import_string, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                (new ImportTask(getActivity(), TripFragment.this, getString(R.string.progress_import), IMPORT_TASK_ID, overwrite.isChecked(), getPersistenceManager())).execute(uri);
                mAttachable.setAttachment(null);
            }
        }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mAttachable.setAttachment(null);
            }
        }).show();
    }

    private final int IMPORT_TASK_ID = 1;

    @Override
    public synchronized void onBooleanTaskComplete(int taskID, Boolean success) {
        if (taskID == IMPORT_TASK_ID) {
            if (success) {
                Toast.makeText(getActivity(), R.string.toast_import_complete, Toast.LENGTH_LONG).show();
                getActivity().finish(); // TODO: Fix this hack - finishing the activity to get rid of the old Intent so
                // we don't reshow import dialog
            } else {
                Toast.makeText(getActivity(), getFlexString(R.string.IMPORT_ERROR), Toast.LENGTH_LONG).show();
            }
            getPersistenceManager().getDatabase().getTripsParallel();
        }
    }

    @Override
    public void onSQLCorruptionException() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.dialog_sql_corrupt_title).setMessage(R.string.dialog_sql_corrupt_message).setPositiveButton(R.string.dialog_sql_corrupt_positive, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int position) {
                Intent intent = EmailAssistant.getEmailDeveloperIntent(getString(R.string.dialog_sql_corrupt_intent_subject), getString(R.string.dialog_sql_corrupt_intent_text));
                getActivity().startActivity(Intent.createChooser(intent, getResources().getString(R.string.dialog_sql_corrupt_chooser)));
                dialog.dismiss();
            }
        }).show();
    }

}