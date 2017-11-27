package com.gettipsi.stripe.util;

import com.google.android.gms.wallet.TransactionInfo;
import com.google.android.gms.wallet.PaymentMethodTokenizationParameters;
import com.google.android.gms.wallet.PaymentsClient;
import com.google.android.gms.wallet.CardRequirements;

public class GooglePayment {
  TransactionInfo transactionInfo;
  PaymentMethodTokenizationParameters params;
  CardRequirements cardRequirements;
  boolean phoneNumberRequired;
  boolean emailRequired;
  boolean shippingAddressRequired;

  public GooglePayment(TransactionInfo transactionInfo,
                       PaymentMethodTokenizationParameters params,
                       CardRequirements cardRequirements,
                       boolean phoneNumberRequired,
                       boolean emailRequired,
                       boolean shippingAddressRequired) {
    this.transactionInfo = transactionInfo;
    this.params = params;
    this.cardRequirements = cardRequirements;
    this.phoneNumberRequired = phoneNumberRequired;
    this.emailRequired = emailRequired;
    this.shippingAddressRequired = shippingAddressRequired;
  }

  public TransactionInfo getTransactionInfo() {
    return transactionInfo;
  }

  public PaymentMethodTokenizationParameters getParams() {
    return params;
  }

  public CardRequirements getCardRequirements() {
    return cardRequirements;
  }

  public boolean isPhoneNumberRequired() {
    return phoneNumberRequired;
  }

  public boolean isEmailRequired() {
    return emailRequired;
  }

  public boolean isShippingAddressRequired() {
    return shippingAddressRequired;
  }
}
