package org.knowm.xchange.binance;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import org.knowm.xchange.BaseExchange;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.binance.dto.account.AssetDetail;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.BinanceExchangeInfo;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.Filter;
import org.knowm.xchange.binance.dto.meta.exchangeinfo.Symbol;
import org.knowm.xchange.binance.service.BinanceAccountService;
import org.knowm.xchange.binance.service.BinanceMarketDataService;
import org.knowm.xchange.binance.service.BinanceTradeService;
import org.knowm.xchange.client.ResilienceRegistries;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.currency.CurrencyPair;
import org.knowm.xchange.dto.meta.CurrencyMetaData;
import org.knowm.xchange.dto.meta.InstrumentMetaData;
import org.knowm.xchange.exceptions.ExchangeException;
import org.knowm.xchange.instrument.Instrument;
import org.knowm.xchange.utils.AuthUtils;
import si.mazi.rescu.SynchronizedValueFactory;

public class BinanceExchange extends BaseExchange implements Exchange {
  public static final String SPECIFIC_PARAM_USE_SANDBOX = "Use_Sandbox";

  private static final String SPOT_URL = "https://api.binance.com";
  protected static ResilienceRegistries RESILIENCE_REGISTRIES;

  protected BinanceExchangeInfo exchangeInfo;
  protected SynchronizedValueFactory<Long> timestampFactory;

  @Override
  protected void initServices() {
    this.timestampFactory = new BinanceTimestampFactory(getExchangeSpecification().getResilience(), getResilienceRegistries());
    this.marketDataService = new BinanceMarketDataService(this, getResilienceRegistries());
    this.tradeService = new BinanceTradeService(this, getResilienceRegistries());
    this.accountService = new BinanceAccountService(this, getResilienceRegistries());
  }

  public SynchronizedValueFactory<Long> getTimestampFactory() {
    return timestampFactory;
  }

  @Override
  public SynchronizedValueFactory<Long> getNonceFactory() {
    throw new UnsupportedOperationException(
        "Binance uses timestamp/recvwindow rather than a nonce");
  }

  public static void resetResilienceRegistries() {
    RESILIENCE_REGISTRIES = null;
  }

  @Override
  public ResilienceRegistries getResilienceRegistries() {
    if (RESILIENCE_REGISTRIES == null) {
      RESILIENCE_REGISTRIES = BinanceResilience.createRegistries();
    }
    return RESILIENCE_REGISTRIES;
  }

  @Override
  public ExchangeSpecification getDefaultExchangeSpecification() {

    ExchangeSpecification spec = new ExchangeSpecification(this.getClass());
    spec.setSslUri(SPOT_URL);
    spec.setHost("www.binance.com");
    spec.setPort(80);
    spec.setExchangeName("Binance");
    spec.setExchangeDescription("Binance Exchange.");
    AuthUtils.setApiAndSecretKey(spec, "binance");
    return spec;
  }

  @Override
  public void applySpecification(ExchangeSpecification exchangeSpecification) {
    concludeHostParams(exchangeSpecification);
    super.applySpecification(exchangeSpecification);
  }

  public BinanceExchangeInfo getExchangeInfo() {
    return exchangeInfo;
  }

  public boolean usingSandbox() {
    return enabledSandbox(exchangeSpecification);
  }

  @Override
  public void remoteInit() {

    try {
      BinanceMarketDataService marketDataService =
          (BinanceMarketDataService) this.marketDataService;
      exchangeInfo = marketDataService.getExchangeInfo();

      BinanceAccountService accountService = (BinanceAccountService) getAccountService();
      Map<String, AssetDetail> assetDetailMap = null;
      if (!usingSandbox() && isAuthenticated()) {
        assetDetailMap = accountService.getAssetDetails(); // not available in sndbox
      }

      postInit(assetDetailMap);

    } catch (Exception e) {
      throw new ExchangeException("Failed to initialize: " + e.getMessage(), e);
    }
  }

  protected void postInit(Map<String, AssetDetail> assetDetailMap) {
    // populate currency pair keys only, exchange does not provide any other metadata for download
    Map<Instrument, InstrumentMetaData> currencyPairs = exchangeMetaData.getInstruments();
    Map<Currency, CurrencyMetaData> currencies = exchangeMetaData.getCurrencies();

    // Clear all hardcoded currencies when loading dynamically from exchange.
    if (assetDetailMap != null) {
      currencies.clear();
    }

    Symbol[] symbols = exchangeInfo.getSymbols();

    for (Symbol symbol : symbols) {
      if (symbol.getStatus().equals("TRADING")) { // Symbols which are trading
        int basePrecision = Integer.parseInt(symbol.getBaseAssetPrecision());
        int counterPrecision = Integer.parseInt(symbol.getQuotePrecision());
        int pairPrecision = 8;
        int amountPrecision = 8;

        BigDecimal minQty = null;
        BigDecimal maxQty = null;
        BigDecimal stepSize = null;

        BigDecimal counterMinQty = null;
        BigDecimal counterMaxQty = null;

        Filter[] filters = symbol.getFilters();

        CurrencyPair currentCurrencyPair =
            new CurrencyPair(symbol.getBaseAsset(), symbol.getQuoteAsset());

        for (Filter filter : filters) {
          if (filter.getFilterType().equals("PRICE_FILTER")) {
            pairPrecision = Math.min(pairPrecision, numberOfDecimals(filter.getTickSize()));
            counterMaxQty = new BigDecimal(filter.getMaxPrice()).stripTrailingZeros();
          } else if (filter.getFilterType().equals("LOT_SIZE")) {
            amountPrecision = Math.min(amountPrecision, numberOfDecimals(filter.getStepSize()));
            minQty = new BigDecimal(filter.getMinQty()).stripTrailingZeros();
            maxQty = new BigDecimal(filter.getMaxQty()).stripTrailingZeros();
            stepSize = new BigDecimal(filter.getStepSize()).stripTrailingZeros();
          } else if (filter.getFilterType().equals("MIN_NOTIONAL")) {
            counterMinQty = new BigDecimal(filter.getMinNotional()).stripTrailingZeros();
          }
        }

        boolean marketOrderAllowed = Arrays.asList(symbol.getOrderTypes()).contains("MARKET");
        currencyPairs.put(
            currentCurrencyPair,
            new InstrumentMetaData.Builder()
                    .tradingFee(BigDecimal.valueOf(0.1))
                    .minimumAmount(minQty)
                    .maximumAmount(maxQty)
                    .counterMinimumAmount(counterMinQty)
                    .counterMaximumAmount(counterMaxQty)
                    .volumeScale(amountPrecision)
                    .priceScale(pairPrecision)
                    .amountStepSize(stepSize)
                    .marketOrderEnabled(marketOrderAllowed)
                    .build());
        Currency baseCurrency = currentCurrencyPair.base;
        CurrencyMetaData baseCurrencyMetaData =
            BinanceAdapters.adaptCurrencyMetaData(
                currencies, baseCurrency, assetDetailMap, basePrecision);
        currencies.put(baseCurrency, baseCurrencyMetaData);

        Currency counterCurrency = currentCurrencyPair.counter;
        CurrencyMetaData counterCurrencyMetaData =
            BinanceAdapters.adaptCurrencyMetaData(
                currencies, counterCurrency, assetDetailMap, counterPrecision);
        currencies.put(counterCurrency, counterCurrencyMetaData);
      }
    }
  }

  private boolean isAuthenticated() {
    return exchangeSpecification != null
        && exchangeSpecification.getApiKey() != null
        && exchangeSpecification.getSecretKey() != null;
  }

  private int numberOfDecimals(String value) {
    return new BigDecimal(value).stripTrailingZeros().scale();
  }

  /** Adjust host parameters depending on exchange specific parameters */
  private static void concludeHostParams(ExchangeSpecification exchangeSpecification) {
    if (exchangeSpecification.getExchangeSpecificParameters() != null) {
      if (enabledSandbox(exchangeSpecification)) {
        exchangeSpecification.setSslUri("https://testnet.binance.vision");
        exchangeSpecification.setHost("testnet.binance.vision");
      }
    }
  }

  private static boolean enabledSandbox(ExchangeSpecification exchangeSpecification) {
    return Boolean.TRUE.equals(
        exchangeSpecification.getExchangeSpecificParametersItem(SPECIFIC_PARAM_USE_SANDBOX));
  }
}
