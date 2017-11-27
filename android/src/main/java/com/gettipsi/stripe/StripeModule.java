package com.gettipsi.stripe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.text.TextUtils;

import com.facebook.react.bridge.ActivityEventListener;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.BaseActivityEventListener;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.gettipsi.stripe.dialog.AddCardDialogFragment;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.BooleanResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.wallet.Cart;
import com.google.android.gms.wallet.CardInfo;
import com.google.android.gms.wallet.FullWallet;
import com.google.android.gms.wallet.FullWalletRequest;
import com.google.android.gms.wallet.IsReadyToPayRequest;
import com.google.android.gms.wallet.LineItem;
import com.google.android.gms.wallet.MaskedWallet;
import com.google.android.gms.wallet.MaskedWalletRequest;
import com.google.android.gms.wallet.PaymentMethodTokenizationParameters;
import com.google.android.gms.wallet.PaymentMethodTokenizationType;
import com.google.android.gms.wallet.Wallet;
import com.google.android.gms.wallet.WalletConstants;
import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.AutoResolveHelper;
import com.google.android.gms.wallet.PaymentData;
import com.google.android.gms.wallet.PaymentDataRequest;
import com.google.android.gms.wallet.PaymentsClient;
import com.google.android.gms.wallet.TransactionInfo;

import com.stripe.android.Stripe;
import com.stripe.android.TokenCallback;
import com.stripe.android.exception.AuthenticationException;
import com.stripe.android.model.BankAccount;
import com.stripe.android.model.Card;
import com.stripe.android.model.Token;
import com.google.android.gms.identity.intents.model.UserAddress;

import com.gettipsi.stripe.util.GooglePaymentsUtil;
import com.gettipsi.stripe.util.GooglePayment;

import org.json.JSONException;

public class StripeModule extends ReactContextBaseJavaModule {
  private static final String TAG = StripeModule.class.getSimpleName();
  private static final String MODULE_NAME = "StripeModule";

  private static final int LOAD_MASKED_WALLET_REQUEST_CODE = 100502;
  private static final int LOAD_FULL_WALLET_REQUEST_CODE = 100503;

  private static final String PURCHASE_CANCELLED = "PURCHASE_CANCELLED";

  //androidPayParams keys:
  private static final String ANDROID_PAY_MODE = "androidPayMode";
  private static final String PRODUCTION = "production";
  private static final String CURRENCY_CODE = "currency_code";
  private static final String SHIPPING_ADDRESS_REQUIRED = "shipping_address_required";
  private static final String TOTAL_PRICE = "total_price";
  private static final String UNIT_PRICE = "unit_price";
  private static final String LINE_ITEMS = "line_items";
  private static final String QUANTITY = "quantity";
  private static final String DESCRIPTION = "description";
  // Arbitrarily-picked result code.
  private static final int LOAD_GOOGLE_PAYMENT_DATA_REQUEST_CODE = 991;

  private int mEnvironment = WalletConstants.ENVIRONMENT_PRODUCTION;
  private Promise payPromise;
  private Promise payWithGooglePromise;
  //payWithGoogle
  private PaymentsClient paymentsClient;

  private String publicKey;
  private Stripe stripe;
  private GoogleApiClient googleApiClient;

  private ReadableMap androidPayParams;

  private static StripeModule instance = null;

  public static StripeModule getInstance() {
    return instance;
  }

  public Stripe getStripe() {
    return stripe;
  }

  private final ActivityEventListener mActivityEventListener = new BaseActivityEventListener() {
  @Override
    public void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
      if (payPromise != null || payWithGooglePromise != null) {
        switch (requestCode) {
          case LOAD_MASKED_WALLET_REQUEST_CODE:
            handleLoadMascedWaletRequest(resultCode, data);
            break;
          case LOAD_FULL_WALLET_REQUEST_CODE:
            if (resultCode == Activity.RESULT_OK) {
              FullWallet fullWallet = data.getParcelableExtra(WalletConstants.EXTRA_FULL_WALLET);
              String tokenJSON = fullWallet.getPaymentMethodToken().getToken();
              Token token = Token.fromString(tokenJSON);
              if (token == null) {
                Log.e(TAG, "onActivityResult: failed to create token from JSON string.");
                payPromise.reject("JsonParsingError", "Failed to create token from JSON string.");
              } else {
                WritableMap result = convertTokenToWritableMap(token);
                if (fullWallet != null) {
                  result.putMap("shippingAddress", convertAddressToWritableMap(fullWallet.getBuyerShippingAddress()));
                }
                payPromise.resolve(result);
              }
            }
            break;
          case LOAD_GOOGLE_PAYMENT_DATA_REQUEST_CODE:
            switch (resultCode) {
              case Activity.RESULT_OK:
                PaymentData paymentData = PaymentData.getFromIntent(data);
                CardInfo info = paymentData.getCardInfo();
                UserAddress shippingAddress = paymentData.getShippingAddress();
                String rawToken = paymentData.getPaymentMethodToken().getToken();
                Token stripeToken = Token.fromString(rawToken);
                if (stripeToken != null && payWithGooglePromise != null) {
                  WritableMap result = convertTokenToWritableMap(stripeToken);
                  result.putMap("shippingAddress", convertAddressToWritableMap(shippingAddress));
                  if(paymentData.getCardInfo() != null){
                    result.putMap("billingAddress", convertAddressToWritableMap(paymentData.getCardInfo().getBillingAddress()));
                  }
                  payWithGooglePromise.resolve(result);
                }
                break;
              case Activity.RESULT_CANCELED:
                break;
              case AutoResolveHelper.RESULT_ERROR:
                // rejecting promise and send error data
                Status status = AutoResolveHelper.getStatusFromIntent(data);
                if(payWithGooglePromise != null){
                  payWithGooglePromise.resolve(status.getStatusCode());
                }
                break;
            }
          default:
            super.onActivityResult(activity, requestCode, resultCode, data);
        }
      }
    }
  };


  public StripeModule(ReactApplicationContext reactContext) {
    super(reactContext);

    // Add the listener for `onActivityResult`
    reactContext.addActivityEventListener(mActivityEventListener);
  }

  @Override
  public String getName() {
    return MODULE_NAME;
  }

  @ReactMethod
  public void init(ReadableMap options) {
    if(exist(options, ANDROID_PAY_MODE, PRODUCTION).toLowerCase().equals("test")) {
      mEnvironment = WalletConstants.ENVIRONMENT_TEST;
      Log.d(TAG, "Environment: test mode");
    }

    publicKey = options.getString("publishableKey");
    stripe = new Stripe(getReactApplicationContext(), publicKey);
  }

  @ReactMethod
  public void deviceSupportsAndroidPay(final Promise promise) {
    if (!isPlayServicesAvailable()) {
      promise.reject(TAG, "Play services are not available!");
      return;
    }
    if (googleApiClient != null && googleApiClient.isConnected()) {
      checkAndroidPayAvaliable(googleApiClient, promise);
    } else if (googleApiClient != null && !googleApiClient.isConnected()) {
      googleApiClient.registerConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
        @Override
        public void onConnected(@Nullable Bundle bundle) {
          checkAndroidPayAvaliable(googleApiClient, promise);
        }

        @Override
        public void onConnectionSuspended(int i) {
          promise.reject(TAG, "onConnectionSuspended i = " + i);
        }
      });
      googleApiClient.connect();
    } else if (googleApiClient == null && getCurrentActivity() != null) {
      googleApiClient = new GoogleApiClient.Builder(getCurrentActivity())
        .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
          @Override
          public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "onConnected: ");
            checkAndroidPayAvaliable(googleApiClient, promise);
          }

          @Override
          public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended: ");
            promise.reject(TAG, "onConnectionSuspended i = " + i);
          }
        })
        .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
          @Override
          public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: ");
            promise.reject(TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
          }
        })
        .addApi(Wallet.API, new Wallet.WalletOptions.Builder()
          .setEnvironment(mEnvironment)
          .setTheme(WalletConstants.THEME_LIGHT)
          .build())
        .build();
      googleApiClient.connect();
    } else {
      promise.reject(TAG, "Unknown error");
    }
  }

  @ReactMethod
  public void deviceSupportsPayWithGoogle(final Promise promise) {
    if (!isPlayServicesAvailable()) {
      promise.reject(TAG, "Play services are not available!");
      return;
    }

    //Should place paymentsClient higher?
    paymentsClient = GooglePaymentsUtil.createPaymentsClient(getCurrentActivity());
    GooglePaymentsUtil.isReadyToPay(paymentsClient).addOnCompleteListener(
      new OnCompleteListener<Boolean>() {
        public void onComplete(Task<Boolean> task) {
          try {
            boolean result = task.getResult(ApiException.class);
            if(result){
              promise.resolve(true);
            }
          } catch (ApiException exception) {
            promise.reject(TAG, "isReadyToPay failed: " + exception.getMessage());
          }
        }
      }
    );
  }

  @ReactMethod
  public void paymentRequestWithPayWithGoogle(final ReadableMap paymentData, final Promise promise) {
    PaymentDataRequest request = GooglePaymentsUtil.createPaymentDataRequest(createGooglePayment(paymentData));
    if(paymentsClient == null){
      paymentsClient = GooglePaymentsUtil.createPaymentsClient(getCurrentActivity());
    }

    if (request != null) {
      payWithGooglePromise = promise;
      AutoResolveHelper.resolveTask(
        paymentsClient.loadPaymentData(request),
        getCurrentActivity(),
        LOAD_GOOGLE_PAYMENT_DATA_REQUEST_CODE);
    }
  }

  @ReactMethod
  public void createTokenWithCard(final ReadableMap cardData, final Promise promise) {
    try {
      stripe.createToken(createCard(cardData),
        publicKey,
        new TokenCallback() {
          public void onSuccess(Token token) {
            promise.resolve(convertTokenToWritableMap(token));
          }

          public void onError(Exception error) {
            error.printStackTrace();
            promise.reject(TAG, error.getMessage());
          }
        });
    } catch (Exception e) {
      promise.reject(TAG, e.getMessage());
    }
  }

  @ReactMethod
  public void createTokenWithBankAccount(final ReadableMap accountData, final Promise promise) {
    try {
      stripe.createBankAccountToken(createBankAccount(accountData),
        publicKey,
        null,
        new TokenCallback() {
          public void onSuccess(Token token) {
            promise.resolve(convertTokenToWritableMap(token));
          }

          public void onError(Exception error) {
            error.printStackTrace();
            promise.reject(TAG, error.getMessage());
          }
        });
    } catch (Exception e) {
      promise.reject(TAG, e.getMessage());
    }
  }

  @ReactMethod
  public void paymentRequestWithCardForm(ReadableMap unused, final Promise promise) {
    if (getCurrentActivity() != null) {
      final AddCardDialogFragment cardDialog = AddCardDialogFragment.newInstance(publicKey);
      cardDialog.setPromise(promise);
      cardDialog.show(getCurrentActivity().getFragmentManager(), "AddNewCard");
    }
  }

  @ReactMethod
  public void paymentRequestWithAndroidPay(final ReadableMap map, final Promise promise) {
    Log.d(TAG, "startAndroidPay: ");
    if (getCurrentActivity() != null) {
      payPromise = promise;
      Log.d(TAG, "startAndroidPay: getCurrentActivity() != null");
      startApiClientAndAndroidPay(getCurrentActivity(), map);
    }
  }

  private void startApiClientAndAndroidPay(final Activity activity, final ReadableMap map) {
    if (googleApiClient != null && googleApiClient.isConnected()) {
      startAndroidPay(map);
    } else {
      googleApiClient = new GoogleApiClient.Builder(activity)
        .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
          @Override
          public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "onConnected: ");
            startAndroidPay(map);
          }

          @Override
          public void onConnectionSuspended(int i) {
            Log.d(TAG, "onConnectionSuspended: ");
            payPromise.reject(TAG, "onConnectionSuspended i = " + i);
          }
        })
        .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
          @Override
          public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d(TAG, "onConnectionFailed: ");
            payPromise.reject(TAG, "onConnectionFailed: " + connectionResult.getErrorMessage());
          }
        })
        .addApi(Wallet.API, new Wallet.WalletOptions.Builder()
          .setEnvironment(mEnvironment)
          .setTheme(WalletConstants.THEME_LIGHT)
          .build())
        .build();
      googleApiClient.connect();
    }
  }

  private void showAndroidPay(final ReadableMap map) {
    androidPayParams = map;
    final String estimatedTotalPrice = map.getString(TOTAL_PRICE);
    final String currencyCode = map.getString(CURRENCY_CODE);
    final Boolean shippingAddressRequired = exist(map, SHIPPING_ADDRESS_REQUIRED, true);
    final MaskedWalletRequest maskedWalletRequest = createWalletRequest(estimatedTotalPrice, currencyCode, shippingAddressRequired);
    Wallet.Payments.loadMaskedWallet(googleApiClient, maskedWalletRequest, LOAD_MASKED_WALLET_REQUEST_CODE);
  }

  private MaskedWalletRequest createWalletRequest(final String estimatedTotalPrice, final String currencyCode, final Boolean shippingAddressRequired) {

    final MaskedWalletRequest maskedWalletRequest = MaskedWalletRequest.newBuilder()

      // Request credit card tokenization with Stripe by specifying tokenization parameters:
      .setPaymentMethodTokenizationParameters(PaymentMethodTokenizationParameters.newBuilder()
        .setPaymentMethodTokenizationType(PaymentMethodTokenizationType.PAYMENT_GATEWAY)
        .addParameter("gateway", "stripe")
        .addParameter("stripe:publishableKey", publicKey)
        .addParameter("stripe:version", BuildConfig.VERSION_NAME)
        .build())
      // You want the shipping address:
      .setShippingAddressRequired(shippingAddressRequired)

      // Price set as a decimal:
      .setEstimatedTotalPrice(estimatedTotalPrice)
      .setCurrencyCode(currencyCode)
      .build();
    return maskedWalletRequest;
  }

  private boolean isPlayServicesAvailable() {
    GoogleApiAvailability googleAPI = GoogleApiAvailability.getInstance();
    int result = googleAPI.isGooglePlayServicesAvailable(getCurrentActivity());
    if (result != ConnectionResult.SUCCESS) {
      return false;
    }
    return true;
  }

  private void androidPayUnavaliableDialog() {
    new AlertDialog.Builder(getCurrentActivity())
      .setMessage(R.string.android_pay_unavaliable)
      .setPositiveButton(android.R.string.ok, null)
      .show();
  }

  private void handleLoadMascedWaletRequest(int resultCode, Intent data) {
    if (resultCode == Activity.RESULT_OK) {
      MaskedWallet maskedWallet = data.getParcelableExtra(WalletConstants.EXTRA_MASKED_WALLET);

      final Cart.Builder cartBuilder = Cart.newBuilder()
        .setCurrencyCode(androidPayParams.getString(CURRENCY_CODE))
        .setTotalPrice(androidPayParams.getString(TOTAL_PRICE));

      final ReadableArray lineItems = androidPayParams.getArray(LINE_ITEMS);
      if (lineItems != null) {
        for (int i = 0; i < lineItems.size(); i++) {
          final ReadableMap lineItem = lineItems.getMap(i);
          cartBuilder.addLineItem(LineItem.newBuilder() // Identify item being purchased
            .setCurrencyCode(lineItem.getString(CURRENCY_CODE))
            .setQuantity(lineItem.getString(QUANTITY))
            .setDescription(DESCRIPTION)
            .setTotalPrice(TOTAL_PRICE)
            .setUnitPrice(UNIT_PRICE)
            .build());
        }
      }

      final FullWalletRequest fullWalletRequest = FullWalletRequest.newBuilder()
        .setCart(cartBuilder.build())
        .setGoogleTransactionId(maskedWallet.getGoogleTransactionId())
        .build();

      Wallet.Payments.loadFullWallet(googleApiClient, fullWalletRequest, LOAD_FULL_WALLET_REQUEST_CODE);
    } else {
      payPromise.reject(PURCHASE_CANCELLED, "Purchase was cancelled");
    }
  }

  private IsReadyToPayRequest doIsReadyToPayRequest() {
    IsReadyToPayRequest request = IsReadyToPayRequest.newBuilder()
      .addAllowedPaymentMethod(WalletConstants.PAYMENT_METHOD_CARD)
      .addAllowedPaymentMethod(WalletConstants.PAYMENT_METHOD_TOKENIZED_CARD)
      .build();
    return request;
  }

  private void checkAndroidPayAvaliable(final GoogleApiClient client, final Promise promise) {
    Wallet.Payments.isReadyToPay(client, doIsReadyToPayRequest()).setResultCallback(
      new ResultCallback<BooleanResult>() {
        @Override
        public void onResult(@NonNull BooleanResult booleanResult) {
          if (booleanResult.getStatus().isSuccess()) {
            promise.resolve(booleanResult.getValue());
          } else {
            // Error making isReadyToPay call
            Log.e(TAG, "isReadyToPay:" + booleanResult.getStatus());
            promise.reject(TAG, booleanResult.getStatus().getStatusMessage());
          }
        }
      });
  }

  private void startAndroidPay(final ReadableMap map) {
    Wallet.Payments.isReadyToPay(googleApiClient, doIsReadyToPayRequest()).setResultCallback(
      new ResultCallback<BooleanResult>() {
        @Override
        public void onResult(@NonNull BooleanResult booleanResult) {
          Log.d(TAG, "onResult: ");
          if (booleanResult.getStatus().isSuccess()) {
            Log.d(TAG, "onResult: booleanResult.getStatus().isSuccess()");
            if (booleanResult.getValue()) {
              // TODO Work only in few countries. I don't now how test it in our countries.
              Log.d(TAG, "onResult: booleanResult.getValue()");
              showAndroidPay(map);
            } else {
              Log.d(TAG, "onResult: !booleanResult.getValue()");
              // Hide Android Pay buttons, show a message that Android Pay
              // cannot be used yet, and display a traditional checkout button
              androidPayUnavaliableDialog();
              payPromise.reject(TAG, "Android Pay unavaliable");
            }
          } else {
            // Error making isReadyToPay call
            Log.e(TAG, "isReadyToPay:" + booleanResult.getStatus());
            androidPayUnavaliableDialog();
            payPromise.reject(TAG, "Error making isReadyToPay call");
          }
        }
      }
    );
  }

  private Card createCard(final ReadableMap cardData) {
    return new Card(
      // required fields
      cardData.getString("number"),
      cardData.getInt("expMonth"),
      cardData.getInt("expYear"),
      // additional fields
      exist(cardData, "cvc"),
      exist(cardData, "name"),
      exist(cardData, "addressLine1"),
      exist(cardData, "addressLine2"),
      exist(cardData, "addressCity"),
      exist(cardData, "addressState"),
      exist(cardData, "addressZip"),
      exist(cardData, "addressCountry"),
      exist(cardData, "brand"),
      exist(cardData, "last4"),
      exist(cardData, "fingerprint"),
      exist(cardData, "funding"),
      exist(cardData, "country"),
      exist(cardData, "currency"),
      exist(cardData, "id")
    );
  }

  private WritableMap convertTokenToWritableMap(Token token) {
    WritableMap newToken = Arguments.createMap();

    if (token == null) return newToken;

    newToken.putString("tokenId", token.getId());
    newToken.putBoolean("livemode", token.getLivemode());
    newToken.putBoolean("used", token.getUsed());
    newToken.putDouble("created", token.getCreated().getTime());

    if (token.getCard() != null) {
      newToken.putMap("card", convertCardToWritableMap(token.getCard()));
    }
    if (token.getBankAccount() != null) {
      newToken.putMap("bankAccount", convertBankAccountToWritableMap(token.getBankAccount()));
    }

    return newToken;
  }

  private WritableMap convertCardToWritableMap(final Card card) {
    WritableMap result = Arguments.createMap();

    if(card == null) return result;

    result.putString("number", card.getNumber());
    result.putString("cvc", card.getCVC() );
    result.putInt("expMonth", card.getExpMonth() );
    result.putInt("expYear", card.getExpYear() );
    result.putString("name", card.getName() );
    result.putString("addressLine1", card.getAddressLine1() );
    result.putString("addressLine2", card.getAddressLine2() );
    result.putString("addressCity", card.getAddressCity() );
    result.putString("addressState", card.getAddressState() );
    result.putString("addressZip", card.getAddressZip() );
    result.putString("addressCountry", card.getAddressCountry() );
    result.putString("last4", card.getLast4() );
    result.putString("brand", card.getBrand() );
    result.putString("funding", card.getFunding() );
    result.putString("fingerprint", card.getFingerprint() );
    result.putString("country", card.getCountry() );
    result.putString("currency", card.getCurrency() );

    return result;
  }

  private WritableMap convertBankAccountToWritableMap(BankAccount account) {
    WritableMap result = Arguments.createMap();

    if(account == null) return result;

    result.putString("routingNumber", account.getRoutingNumber());
    result.putString("accountNumber", account.getAccountNumber());
    result.putString("countryCode", account.getCountryCode());
    result.putString("currency", account.getCurrency());
    result.putString("accountHolderName", account.getAccountHolderName());
    result.putString("accountHolderType", account.getAccountHolderType());
    result.putString("fingerprint", account.getFingerprint());
    result.putString("bankName", account.getBankName());
    result.putString("last4", account.getLast4());

    return result;
  }

  private WritableMap convertAddressToWritableMap(final UserAddress address){
    WritableMap result = Arguments.createMap();

    if(address == null) return result;

    putIfExist(result, "address1", address.getAddress1());
    putIfExist(result, "address2", address.getAddress2());
    putIfExist(result, "address3", address.getAddress3());
    putIfExist(result, "address4", address.getAddress4());
    putIfExist(result, "address5", address.getAddress5());
    putIfExist(result, "administrativeArea", address.getAdministrativeArea());
    putIfExist(result, "companyName", address.getCompanyName());
    putIfExist(result, "countryCode", address.getCountryCode());
    putIfExist(result, "locality", address.getLocality());
    putIfExist(result, "name", address.getName());
    putIfExist(result, "phoneNumber", address.getPhoneNumber());
    putIfExist(result, "postalCode", address.getPostalCode());
    putIfExist(result, "sortingCode", address.getSortingCode());

    putIfExist(result, "formattedAddress", formatUsAddress(address));
    return result;
  }

  private static String formatUsAddress(UserAddress address) {
    StringBuilder builder = new StringBuilder();
    builder.append("\n");
    if (appendIfValid(address.getAddress1(), builder)) builder.append(", ");
    if (appendIfValid(address.getLocality(), builder)) builder.append(", ");
    if (appendIfValid(address.getAdministrativeArea(), builder)) builder.append(", ");
    appendIfValid(address.getCountryCode(), builder);
    return builder.toString();
  }

  private static boolean appendIfValid(String string, StringBuilder builder) {
    if (string != null && string.length() > 0) {
      builder.append(string);
      return true;
    }
    return false;
  }


  private BankAccount createBankAccount(ReadableMap accountData) {
    BankAccount account = new BankAccount(
      // required fields only
      accountData.getString("accountNumber"),
      accountData.getString("countryCode"),
      accountData.getString("currency"),
      exist(accountData, "routingNumber", "")
    );
    account.setAccountHolderName(exist(accountData, "accountHolderName"));
    account.setAccountHolderType(exist(accountData, "accountHolderType"));

    return account;
  }

  private GooglePayment createGooglePayment(ReadableMap paymentData) {
    GooglePayment payment = new GooglePayment(
      GooglePaymentsUtil.createTransaction(paymentData.getString("price")),
      GooglePaymentsUtil.createTokenizationParameters(publicKey),
      GooglePaymentsUtil.createCardRequirements(),
      exist(paymentData, "phoneNumberRequired", false),
      exist(paymentData, "emailRequired", true),
      exist(paymentData, "shippingAddressRequired", true)
    );
    return payment;
  }

  private String exist(final ReadableMap map, final String key, final String def) {
    if (map.hasKey(key)) {
      return map.getString(key);
    } else {
      // If map don't have some key - we must pass to constructor default value.
      return def;
    }
  }

  private void putIfExist(final WritableMap map, final String key, final String value) {
    if (!TextUtils.isEmpty(value)) {
      map.putString(key, value);
    }
  }

  private Boolean exist(final ReadableMap map, final String key, final Boolean def) {
    if (map.hasKey(key)) {
      return map.getBoolean(key);
    } else {
      // If map don't have some key - we must pass to constructor default value.
      return def;
    }
  }

  private String exist(final ReadableMap map, final String key) {
    return exist(map, key, (String) null);
  }
}
