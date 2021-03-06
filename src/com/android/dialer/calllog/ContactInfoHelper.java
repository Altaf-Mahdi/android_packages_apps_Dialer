/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.dialer.calllog;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteFullException;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.DisplayNameSources;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Telephony;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.widget.FrameLayout;

import com.nispok.snackbar.Snackbar;
import com.nispok.snackbar.SnackbarManager;
import com.nispok.snackbar.listeners.EventListener;

import com.android.contacts.common.util.Constants;
import com.android.contacts.common.util.PermissionsUtil;
import com.android.contacts.common.util.PhoneNumberHelper;
import com.android.contacts.common.util.UriUtils;
import com.android.dialer.R;
import com.android.dialer.lookup.ContactBuilder;
import com.android.dialer.lookup.LookupCache;
import com.android.dialer.service.CachedNumberLookupService;
import com.android.dialer.service.CachedNumberLookupService.CachedContactInfo;
import com.android.dialer.util.MetricsHelper;
import com.android.dialer.util.TelecomUtil;
import com.android.dialerbind.ObjectFactory;

import com.cyanogen.lookup.phonenumber.contract.LookupProvider;
import com.cyanogen.lookup.phonenumber.provider.LookupProviderImpl;
import com.cyanogen.lookup.phonenumber.request.LookupRequest;
import com.cyanogen.lookup.phonenumber.response.LookupResponse;
import com.cyanogen.lookup.phonenumber.response.StatusCode;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Utility class to look up the contact information for a given number.
 */
public class ContactInfoHelper {
    private static final String TAG = ContactInfoHelper.class.getSimpleName();

    /**
     * If this boolean parameter is set to true, then the appended query is treated as a
     * InCallApi plugin contact ID and the lookup will be performed against InCallApi contacts in
     * the user's contacts.
     */
    // TODO: Move this to a more central place
    private static final String QUERY_PARAMETER_INCALLAPI_ID = "incallapi_contactid";

    private final Context mContext;
    private final String mCurrentCountryIso;
    private final LookupProvider mLookupProvider;

    private static final CachedNumberLookupService mCachedNumberLookupService =
            ObjectFactory.newCachedNumberLookupService();

    public ContactInfoHelper(Context context, String currentCountryIso,
                             LookupProvider lookupProvider) {
        mContext = context;
        mCurrentCountryIso = currentCountryIso;
        mLookupProvider = lookupProvider;
    }

    /**
     * Returns the contact information for the given number.
     * <p>
     * If the number does not match any contact, returns a contact info containing only the number
     * and the formatted number.
     * <p>
     * If an error occurs during the lookup, it returns null.
     *
     * @param number the number to look up
     * @param countryIso the country associated with this number
     * @param isInCallPluginContactId true if number is an InCallApi plugin contact id
     */
    public ContactInfo lookupNumber(String number, String countryIso,
            boolean isInCallPluginContactId) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }
        final ContactInfo info;

        // Determine the contact info.
        if (PhoneNumberHelper.isUriNumber(number)) {
            // This "number" is really a SIP address.
            ContactInfo sipInfo = queryContactInfoForSipAddress(number);
            if (sipInfo == null || sipInfo == ContactInfo.EMPTY) {
                // Check whether the "username" part of the SIP address is
                // actually the phone number of a contact.
                String username = PhoneNumberHelper.getUsernameFromUriNumber(number);
                if (PhoneNumberUtils.isGlobalPhoneNumber(username)) {
                    sipInfo = queryContactInfoForPhoneNumber(username, countryIso, false);
                }
            }
            info = sipInfo;
        } else {
            // Look for a contact that has the given phone number.
            ContactInfo phoneInfo =
                    queryContactInfoForPhoneNumber(number, countryIso, isInCallPluginContactId);

            // If we got a result, but the data is invalid, bail out and try again later.
            if (phoneInfo != null && phoneInfo.isBadData) {
                return null;
            }

            if (phoneInfo == null || phoneInfo == ContactInfo.EMPTY) {
                // Check whether the phone number has been saved as an "Internet call" number.
                phoneInfo = queryContactInfoForSipAddress(number);
            }
            info = phoneInfo;
        }

        final ContactInfo updatedInfo;
        if (info == null) {
            // The lookup failed.
            updatedInfo = null;
        } else {
            // If we did not find a matching contact, generate an empty contact info for the number.
            if (info == ContactInfo.EMPTY) {
                // Did not find a matching contact.
                updatedInfo = new ContactInfo();
                updatedInfo.number = number;
                updatedInfo.formattedNumber = formatPhoneNumber(number, null, countryIso);
                updatedInfo.normalizedNumber = PhoneNumberUtils.formatNumberToE164(
                        number, countryIso);
                updatedInfo.lookupUri = createTemporaryContactUri(updatedInfo.formattedNumber);
            } else {
                updatedInfo = info;
            }
        }
        return updatedInfo;
    }

    /**
     * Creates a JSON-encoded lookup uri for a unknown number without an associated contact
     *
     * @param number - Unknown phone number
     * @return JSON-encoded URI that can be used to perform a lookup when clicking on the quick
     *         contact card.
     */
    private static Uri createTemporaryContactUri(String number) {
        try {
            final JSONObject contactRows = new JSONObject().put(Phone.CONTENT_ITEM_TYPE,
                    new JSONObject().put(Phone.NUMBER, number).put(Phone.TYPE, Phone.TYPE_CUSTOM));

            final String jsonString = new JSONObject().put(Contacts.DISPLAY_NAME, number)
                    .put(Contacts.DISPLAY_NAME_SOURCE, DisplayNameSources.PHONE)
                    .put(Contacts.CONTENT_ITEM_TYPE, contactRows).toString();

            return Contacts.CONTENT_LOOKUP_URI
                    .buildUpon()
                    .appendPath(Constants.LOOKUP_URI_ENCODED)
                    .appendQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY,
                            String.valueOf(Long.MAX_VALUE))
                    .encodedFragment(jsonString)
                    .build();
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Looks up a contact using the given URI.
     * <p>
     * It returns null if an error occurs, {@link ContactInfo#EMPTY} if no matching contact is
     * found, or the {@link ContactInfo} for the given contact.
     * <p>
     * The {@link ContactInfo#formattedNumber} field is always set to {@code null} in the returned
     * value.
     */
    private ContactInfo lookupContactFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        if (!PermissionsUtil.hasContactsPermissions(mContext)) {
            return ContactInfo.EMPTY;
        }
        final ContactInfo info;
        Cursor phonesCursor =
                mContext.getContentResolver().query(uri, PhoneQuery._PROJECTION, null, null, null);

        String data = uri.getPathSegments().size() > 1 ? uri.getLastPathSegment() : "";

        if (phonesCursor != null) {
            try {
                if (phonesCursor.moveToFirst()) {
                    info = new ContactInfo();
                    String lookupKey = null;
                    long contactId = phonesCursor.getLong(PhoneQuery.PERSON_ID);
                    info.type = phonesCursor.getInt(PhoneQuery.PHONE_TYPE);
                    info.label = phonesCursor.getString(PhoneQuery.LABEL);
                    info.number = phonesCursor.getString(PhoneQuery.MATCHED_NUMBER);
                    info.normalizedNumber = phonesCursor.getString(PhoneQuery.NORMALIZED_NUMBER);
                    info.formattedNumber = null;
                    if (PhoneNumberUtils.isGlobalPhoneNumber(data)) {
                        lookupKey = phonesCursor.getString(PhoneQuery.LOOKUP_KEY);
                        info.name = phonesCursor.getString(PhoneQuery.NAME);
                        info.photoId = phonesCursor.getLong(PhoneQuery.PHOTO_ID);
                        info.photoUri = UriUtils.parseUriOrNull(
                                phonesCursor.getString(PhoneQuery.PHOTO_URI));
                    } else {
                        try {
                            lookupKey = phonesCursor.getString(
                                    phonesCursor.getColumnIndexOrThrow("lookup"));
                            info.name = phonesCursor.getString(
                                    phonesCursor.getColumnIndexOrThrow("display_name"));
                            info.photoId = phonesCursor.getLong(
                                    phonesCursor.getColumnIndexOrThrow("photo_id"));
                            info.photoUri = UriUtils.parseUriOrNull(phonesCursor.getString(
                                    phonesCursor.getColumnIndexOrThrow("photo_uri")));
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Contact information invalid, cannot find needed column(s)",
                                    e);
                        }
                    }
                    info.lookupKey = lookupKey;
                    info.lookupUri = Contacts.getLookupUri(contactId, lookupKey);
                } else {
                    info = ContactInfo.EMPTY;
                }
            } finally {
                phonesCursor.close();
            }
        } else {
            // Failed to fetch the data, ignore this request.
            info = null;
        }
        return info;
    }

    /**
     * Determines the contact information for the given SIP address.
     * <p>
     * It returns the contact info if found.
     * <p>
     * If no contact corresponds to the given SIP address, returns {@link ContactInfo#EMPTY}.
     * <p>
     * If the lookup fails for some other reason, it returns null.
     */
    private ContactInfo queryContactInfoForSipAddress(String sipAddress) {
        if (TextUtils.isEmpty(sipAddress)) {
            return null;
        }
        final ContactInfo info;

        // "contactNumber" is a SIP address, so use the PhoneLookup table with the SIP parameter.
        Uri.Builder uriBuilder = PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon();
        uriBuilder.appendPath(Uri.encode(sipAddress));
        uriBuilder.appendQueryParameter(PhoneLookup.QUERY_PARAMETER_SIP_ADDRESS, "1");
        return lookupContactFromUri(uriBuilder.build());
    }

    /**
     * Determines the contact information for the given phone number.
     * <p>
     * It returns the contact info if found.
     * <p>
     * If no contact corresponds to the given phone number, returns {@link ContactInfo#EMPTY}.
     * <p>
     * If the lookup fails for some other reason, it returns null.
     */
    private ContactInfo queryContactInfoForPhoneNumber(String number, String countryIso,
            boolean isInCallPluginContactId) {
        if (TextUtils.isEmpty(number)) {
            return null;
        }
        String contactNumber = number;
        if (!TextUtils.isEmpty(countryIso)) {
            // Normalize the number: this is needed because the PhoneLookup query below does not
            // accept a country code as an input.
            String numberE164 = PhoneNumberUtils.formatNumberToE164(number, countryIso);
            if (!TextUtils.isEmpty(numberE164)) {
                // Only use it if the number could be formatted to E164.
                contactNumber = numberE164;
            }
        }

        // The "contactNumber" is a regular phone number, so use the PhoneLookup table.
        Uri.Builder uriBuilder = PhoneLookup.ENTERPRISE_CONTENT_FILTER_URI.buildUpon();
        uriBuilder.appendPath(Uri.encode(contactNumber));
        uriBuilder.appendQueryParameter(QUERY_PARAMETER_INCALLAPI_ID,
                String.valueOf(isInCallPluginContactId));
        ContactInfo info = lookupContactFromUri(uriBuilder.build());
        boolean isLocalContact = info != null && info != ContactInfo.EMPTY;
        if (info != null && info != ContactInfo.EMPTY) {
            info.formattedNumber = formatPhoneNumber(number, null, countryIso);
        } else if (LookupCache.hasCachedContact(mContext, number)) {
            info = LookupCache.getCachedContact(mContext, number);
        } else if (mCachedNumberLookupService != null) {
            CachedContactInfo cacheInfo =
                    mCachedNumberLookupService.lookupCachedContactFromNumber(mContext, number);
            if (cacheInfo != null) {
                info = cacheInfo.getContactInfo().isBadData ? null : cacheInfo.getContactInfo();
            } else {
                info = null;
            }
        }
        // always do a LookupProvider search, if available, for a non-contact
        if (mLookupProvider.isEnabled() && !isLocalContact) {
            LookupResponse response = mLookupProvider.blockingFetchInfo(
                    new LookupRequest(PhoneNumberUtils.formatNumberToE164(number, countryIso),
                            null, LookupRequest.RequestOrigin.OTHER)
                    );

            if (response != null) {
                if (response.mStatusCode == StatusCode.FAIL) {
                    info.isBadData = true;
                } else if (response.mStatusCode == StatusCode.SUCCESS) {
                    logSuccessfulFetch();
                    final String formattedNumber = formatPhoneNumber(response.mNumber, null, countryIso);
                    // map LookupResponse to ContactInfo
                    ContactInfo contactInfo = new ContactInfo();
                    contactInfo.sourceType = 1;
                    contactInfo.lookupProviderName = response.mProviderName;
                    contactInfo.name = response.mName;
                    contactInfo.number = formatPhoneNumber(response.mNumber, null, countryIso);
                    contactInfo.city = response.mCity;
                    contactInfo.country = response.mCountry;
                    contactInfo.address = response.mAddress;
                    contactInfo.photoUrl = response.mPhotoUrl;
                    contactInfo.isSpam = response.mIsSpam;
                    contactInfo.spamCount = response.mSpamCount;
                    contactInfo.attributionDrawable = response.mAttributionLogo;

                    StringBuilder succinctLocation = new StringBuilder();
                    // convert country code to country name
                    String country = new Locale("", response.mCountry).getDisplayCountry();

                    if (!TextUtils.isEmpty(response.mCity)) {
                        succinctLocation.append(response.mCity);
                    }
                    if (!TextUtils.isEmpty(country)) {
                        if (succinctLocation.length() > 0) {
                            succinctLocation.append(", ");
                        }
                        succinctLocation.append(country);
                    }
                    contactInfo.label = succinctLocation.toString();

                    // construct encoded lookup uri
                    ContactBuilder contactBuilder = new ContactBuilder(ContactBuilder.REVERSE_LOOKUP,
                            response.mNumber, formattedNumber);
                    contactBuilder.setInfoProviderName(response.mProviderName);
                    contactBuilder.setPhotoUrl(response.mPhotoUrl);
                    contactBuilder.setName(ContactBuilder.Name.createDisplayName(response.mName));
                    contactBuilder.setIsSpam(response.mIsSpam);
                    contactBuilder.setSpamCount(response.mSpamCount);

                    contactInfo.lookupUri = contactBuilder.build().lookupUri;
                    info = contactInfo;
                }
            }
        }
        return info;
    }

    /**
     * Format the given phone number
     *
     * @param number the number to be formatted.
     * @param normalizedNumber the normalized number of the given number.
     * @param countryIso the ISO 3166-1 two letters country code, the country's convention will be
     *        used to format the number if the normalized phone is null.
     *
     * @return the formatted number, or the given number if it was formatted.
     */
    private String formatPhoneNumber(String number, String normalizedNumber, String countryIso) {
        if (TextUtils.isEmpty(number)) {
            return "";
        }
        // If "number" is really a SIP address, don't try to do any formatting at all.
        if (PhoneNumberHelper.isUriNumber(number)) {
            return number;
        }
        if (TextUtils.isEmpty(countryIso)) {
            countryIso = mCurrentCountryIso;
        }
        return PhoneNumberUtils.formatNumber(number, normalizedNumber, countryIso);
    }

    /**
     * Stores differences between the updated contact info and the current call log contact info.
     *
     * @param number The number of the contact.
     * @param countryIso The country associated with this number.
     * @param updatedInfo The updated contact info.
     * @param callLogInfo The call log entry's current contact info.
     */
    public void updateCallLogContactInfo(String number, String countryIso, ContactInfo updatedInfo,
            ContactInfo callLogInfo) {
        if (!PermissionsUtil.hasPermission(mContext, android.Manifest.permission.WRITE_CALL_LOG)) {
            return;
        }

        final ContentValues values = new ContentValues();
        boolean needsUpdate = false;

        if (callLogInfo != null) {
            if (!TextUtils.equals(updatedInfo.name, callLogInfo.name)) {
                values.put(Calls.CACHED_NAME, updatedInfo.name);
                needsUpdate = true;
            }

            if (updatedInfo.type != callLogInfo.type) {
                values.put(Calls.CACHED_NUMBER_TYPE, updatedInfo.type);
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.label, callLogInfo.label)) {
                values.put(Calls.CACHED_NUMBER_LABEL, updatedInfo.label);
                needsUpdate = true;
            }

            if (!UriUtils.areEqual(updatedInfo.lookupUri, callLogInfo.lookupUri)) {
                values.put(Calls.CACHED_LOOKUP_URI, UriUtils.uriToString(updatedInfo.lookupUri));
                needsUpdate = true;
            }

            // Only replace the normalized number if the new updated normalized number isn't empty.
            if (!TextUtils.isEmpty(updatedInfo.normalizedNumber) &&
                    !TextUtils.equals(updatedInfo.normalizedNumber, callLogInfo.normalizedNumber)) {
                values.put(Calls.CACHED_NORMALIZED_NUMBER, updatedInfo.normalizedNumber);
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.number, callLogInfo.number)) {
                values.put(Calls.CACHED_MATCHED_NUMBER, updatedInfo.number);
                needsUpdate = true;
            }

            if (updatedInfo.photoId != callLogInfo.photoId) {
                values.put(Calls.CACHED_PHOTO_ID, updatedInfo.photoId);
                needsUpdate = true;
            }

            final Uri updatedPhotoUriContactsOnly =
                    UriUtils.nullForNonContactsUri(updatedInfo.photoUri);
            if (!UriUtils.areEqual(updatedPhotoUriContactsOnly, callLogInfo.photoUri)) {
                values.put(Calls.CACHED_PHOTO_URI,
                        UriUtils.uriToString(updatedPhotoUriContactsOnly));
                needsUpdate = true;
            }

            if (!TextUtils.equals(updatedInfo.formattedNumber, callLogInfo.formattedNumber)) {
                values.put(Calls.CACHED_FORMATTED_NUMBER, updatedInfo.formattedNumber);
                needsUpdate = true;
            }
        } else {
            // No previous values, store all of them.
            values.put(Calls.CACHED_NAME, updatedInfo.name);
            values.put(Calls.CACHED_NUMBER_TYPE, updatedInfo.type);
            values.put(Calls.CACHED_NUMBER_LABEL, updatedInfo.label);
            values.put(Calls.CACHED_LOOKUP_URI, UriUtils.uriToString(updatedInfo.lookupUri));
            values.put(Calls.CACHED_MATCHED_NUMBER, updatedInfo.number);
            values.put(Calls.CACHED_NORMALIZED_NUMBER, updatedInfo.normalizedNumber);
            values.put(Calls.CACHED_PHOTO_ID, updatedInfo.photoId);
            values.put(Calls.CACHED_PHOTO_URI, UriUtils.uriToString(
                    UriUtils.nullForNonContactsUri(updatedInfo.photoUri)));
            values.put(Calls.CACHED_FORMATTED_NUMBER, updatedInfo.formattedNumber);
            needsUpdate = true;
        }

        if (!needsUpdate) {
            return;
        }

        try {
            if (countryIso == null) {
                mContext.getContentResolver().update(
                        TelecomUtil.getCallLogUri(mContext),
                        values,
                        Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " IS NULL",
                        new String[]{ number });
            } else {
                mContext.getContentResolver().update(
                        TelecomUtil.getCallLogUri(mContext),
                        values,
                        Calls.NUMBER + " = ? AND " + Calls.COUNTRY_ISO + " = ?",
                        new String[]{ number, countryIso });
            }
        } catch (SQLiteFullException e) {
            Log.e(TAG, "Unable to update contact info in call log db", e);
        }
    }

    /**
     * Returns the contact information stored in an entry of the call log.
     *
     * @param c A cursor pointing to an entry in the call log.
     */
    public static ContactInfo getContactInfo(Context context, Cursor c) {
        ContactInfo info = new ContactInfo();

        info.lookupUri = UriUtils.parseUriOrNull(c.getString(CallLogQuery.CACHED_LOOKUP_URI));
        info.name = c.getString(CallLogQuery.CACHED_NAME);
        info.type = c.getInt(CallLogQuery.CACHED_NUMBER_TYPE);
        info.label = c.getString(CallLogQuery.CACHED_NUMBER_LABEL);
        String matchedNumber = c.getString(CallLogQuery.CACHED_MATCHED_NUMBER);
        info.number = matchedNumber == null ? c.getString(CallLogQuery.NUMBER) : matchedNumber;
        info.normalizedNumber = c.getString(CallLogQuery.CACHED_NORMALIZED_NUMBER);
        info.photoId = c.getLong(CallLogQuery.CACHED_PHOTO_ID);
        info.photoUri = UriUtils.nullForNonContactsUri(
                UriUtils.parseUriOrNull(c.getString(CallLogQuery.CACHED_PHOTO_URI)));
        info.formattedNumber = c.getString(CallLogQuery.CACHED_FORMATTED_NUMBER);

        final String componentString = c.getString(CallLogQuery.ACCOUNT_COMPONENT_NAME);
        final String accountId = c.getString(CallLogQuery.ACCOUNT_ID);
        final String countryIso = c.getString(CallLogQuery.COUNTRY_ISO);
        info.isInCallPluginContactId = isInCallPluginContactId(context,
                PhoneAccountUtils.getAccount(componentString, accountId),
                info.number,
                countryIso,
                c.getString(CallLogQuery.PLUGIN_PACKAGE_NAME));

        return info;
    }

    /**
     * Given a contact's sourceType, return true if the contact is a business
     *
     * @param sourceType sourceType of the contact. This is usually populated by
     *        {@link #mCachedNumberLookupService}.
     */
    public boolean isBusiness(int sourceType) {
        return mCachedNumberLookupService != null
                && mCachedNumberLookupService.isBusiness(sourceType);
    }

    /**
     * This function looks at a contact's source and determines if the user can
     * mark caller ids from this source as invalid.
     *
     * @param sourceType The source type to be checked
     * @param objectId The ID of the Contact object.
     * @return true if contacts from this source can be marked with an invalid caller id
     */
    public boolean canReportAsInvalid(int sourceType, String objectId) {
        return mCachedNumberLookupService != null
                && mCachedNumberLookupService.canReportAsInvalid(sourceType, objectId);
    }

    /**
     * Requests the given number to be added to the phone blacklist
     *
     * @param number the number to be blacklisted
     */
    public void addNumberToBlacklist(String number) {
        ContentValues cv = new ContentValues();
        cv.put(Telephony.Blacklist.PHONE_MODE, 1);

        Uri uri = Uri.withAppendedPath(Telephony.Blacklist.CONTENT_FILTER_BYNUMBER_URI, number);
        int count = mContext.getContentResolver().update(uri, cv, null, null);

        // show a snackbar message
        if (count != 0) {
            String message = mContext.getString(
                    R.string.toast_added_to_blacklist, number);
            showBlacklistSnackbar(message);
        }
    }

    /**
     * Requests the given number to be removed from phone blacklist
     *
     * @param number the number to be removed from blacklist
     */
    public void removeNumberFromBlacklist(String number) {
        Uri uri = Uri.withAppendedPath(Telephony.Blacklist.CONTENT_FILTER_BYNUMBER_URI, number);
        int count = mContext.getContentResolver().delete(uri, null, null);

        // show a snackbar message
        if (count != 0) {
            String message = mContext.getString(
                    R.string.toast_removed_from_blacklist, number);
            showBlacklistSnackbar(message);
        }
    }

    /**
     * Snackbar message which displays the number being added/removed from phone blacklist
     *
     * @param message, message to be shown
     */
    void showBlacklistSnackbar(String message) {
        Activity realActivity = ((Activity)mContext).getParent();
        if (realActivity == null) {
            realActivity = (Activity)mContext;
        }
        final FrameLayout fabView = (FrameLayout) realActivity.findViewById(
                R.id.floating_action_button_container);
        SnackbarManager.show(
            Snackbar.with(mContext)
                .text(message)
                .duration(Snackbar.SnackbarDuration.LENGTH_SHORT)
                .color(mContext.getResources().getColor(
                        R.color.call_log_action_text))
                .eventListener(new EventListener() {
                    @Override
                    public void onShow(Snackbar snackbar) {
                        if (fabView != null) {
                            fabView.animate().translationY(-snackbar.getHeight()).start();
                        }
                    }

                    @Override
                    public void onDismiss(Snackbar snackbar) {
                        if (fabView != null) {
                            fabView.animate().translationY(0).start();
                        }
                    }

                    @Override
                    public void onShowByReplace(Snackbar snackbar) {}

                    @Override
                    public void onShown(Snackbar snackbar) {}

                    @Override
                    public void onDismissByReplace(Snackbar snackbar) {}

                    @Override
                    public void onDismissed(Snackbar snackbar) {}

                })
            , realActivity);
    }

    private void logSuccessfulFetch() {
        MetricsHelper.Field field = new MetricsHelper.Field(
                MetricsHelper.Fields.PROVIDER_PACKAGE_NAME,
                mLookupProvider.getUniqueIdentifier());
        MetricsHelper.sendEvent(
                MetricsHelper.Categories.PROVIDER_PROVIDED_INFORMATION,
                MetricsHelper.Actions.PROVIDED_INFORMATION,
                MetricsHelper.State.CALL_LOG,
                field);
    }

    public static boolean isInCallPluginContactId(Context context,
            PhoneAccountHandle accountHandle, String number, String countryIso, String pluginName) {
        boolean isInCallPluginContactId = accountHandle == null &&
                !PhoneNumberHelper.isValidNumber(context, number, countryIso) &&
                !TextUtils.isEmpty(pluginName);

        return isInCallPluginContactId;
    }
}
