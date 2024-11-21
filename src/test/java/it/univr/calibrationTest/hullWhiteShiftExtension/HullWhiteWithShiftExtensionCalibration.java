package it.univr.calibrationTest.hullWhiteShiftExtension;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import it.univr.calibrationHullWhite.shfiftExtension.HullWhiteModelWithShiftExtension;
import net.finmath.exception.CalculationException;
import net.finmath.functions.AnalyticFormulas;
import net.finmath.marketdata.calibration.CalibratedCurves;
import net.finmath.marketdata.calibration.CalibratedCurves.CalibrationSpec;
import net.finmath.marketdata.model.AnalyticModel;
import net.finmath.marketdata.model.AnalyticModelFromCurvesAndVols;
import net.finmath.marketdata.model.curves.Curve;
import net.finmath.marketdata.model.curves.CurveInterpolation;
import net.finmath.marketdata.model.curves.CurveInterpolation.ExtrapolationMethod;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationEntity;
import net.finmath.marketdata.model.curves.CurveInterpolation.InterpolationMethod;
import net.finmath.marketdata.model.curves.DiscountCurve;
import net.finmath.marketdata.model.curves.DiscountCurveInterpolation;
import net.finmath.marketdata.model.curves.ForwardCurve;
import net.finmath.marketdata.model.curves.ForwardCurveFromDiscountCurve;
import net.finmath.marketdata.model.curves.ForwardCurveInterpolation;
import net.finmath.montecarlo.BrownianMotion;
import net.finmath.montecarlo.BrownianMotionFromMersenneRandomNumbers;
import net.finmath.montecarlo.RandomVariableFromArrayFactory;
import net.finmath.montecarlo.interestrate.CalibrationProduct;
import net.finmath.montecarlo.interestrate.models.covariance.AbstractShortRateVolatilityModel;
import net.finmath.montecarlo.interestrate.models.covariance.ShortRateVolatilityModelParametric;
import net.finmath.montecarlo.interestrate.models.covariance.ShortRateVolatilityModelPiecewiseConstant;
import net.finmath.montecarlo.interestrate.products.AbstractTermStructureMonteCarloProduct;
import net.finmath.montecarlo.interestrate.TermStructureMonteCarloSimulationFromTermStructureModel;
import net.finmath.montecarlo.interestrate.products.SwaptionSimple;
import net.finmath.montecarlo.process.EulerSchemeFromProcessModel;
import net.finmath.optimizer.SolverException;
import net.finmath.time.Schedule;
import net.finmath.time.ScheduleGenerator;
import net.finmath.time.TimeDiscretization;
import net.finmath.time.TimeDiscretizationFromArray;
import net.finmath.time.businessdaycalendar.BusinessdayCalendar.DateRollConvention;
import net.finmath.time.businessdaycalendar.BusinessdayCalendarExcludingTARGETHolidays;
import net.finmath.time.daycount.DayCountConvention_ACT_365;

/*
 * In thiss class class, we present the calibration of the Hull White model with deterministic shift extension.
 * The model assumes picewise constant parameters. For semplicity, we will assume only the volatility is piecewise constant,
 * while the other parameters remain fixed. Model parameters are calibrated to quoted ATM co-terminal swaptions.
 */

public class HullWhiteWithShiftExtensionCalibration {

	public static void main(String[] args) throws SolverException, CloneNotSupportedException, CalculationException {
		// TODO Auto-generated method stub
		HullWhiteWithShiftExtensionCalibration.getCalibratedHullWhiteWhitShiftExtension();
	}

	public static HullWhiteModelWithShiftExtension getCalibratedHullWhiteWhitShiftExtension () throws SolverException, CloneNotSupportedException, CalculationException {
		
		
		final int numberOfPaths = 1000;
		
		/*
		 * Calibration test
		 */
		System.out.println("Calbration to co-terminal ATM Swaptions. \n");
		
		/*
		 * Calibration of rate curves
		 */
		
		System.out.println("Calibration of rate curves:");
		//This method provide the output (curves) of bootstrap
		final AnalyticModel curveModel = MultiCurveBootstrap.getCurves();
		
		//This model is a multicurve model
		final ForwardCurve forwardCurve = curveModel.getForwardCurve("forward-EUR-3M");
		final DiscountCurve discountCurve = curveModel.getDiscountCurve("discount-EUR-OIS");
		
		
		System.out.println();
		
		
		System.out.println("Brute force Monte-Carlo calibration of model volatilities:");

		
		/*
		 * Create a set of calibration products. In this case, we initialize the swaptions.
		 */
		
		//Create array on which the calibration will be performed 
		final ArrayList<String>  calibrationItemNames = new ArrayList<>();
		final ArrayList<CalibrationProduct>  calibrationProducts = new ArrayList<>();
		
		//This is the frequency of payments: 6 months
		final double 	swapPeriodLength 	= 0.5;
		
		//Create a co-terminals (atmExpiry + atmTneor = 11Y)
		
		//This is the option's maturity
		final String [] atmExpiries = {"1Y", "2Y", "3Y", "4Y", "5Y", "7Y", "10Y"};
		//This is the swap's lenght
		final String[] atmTenors = {"10Y", "9Y", "8Y", "7Y", "6Y", "4Y", "1Y"};
		
		//These are the real quotes of swaption (the swaptions are quoted in term of Normal/Bachelier implied voltilities
		final double[] atmNormalVolatilities = {0.00504, 0.005, 0.00495, 0.00454, 0.00418, 0.00404, 0.00394};
		
		final LocalDate referenceDate = LocalDate.of(2016, Month.SEPTEMBER, 30);
		final BusinessdayCalendarExcludingTARGETHolidays cal = new BusinessdayCalendarExcludingTARGETHolidays();
		final DayCountConvention_ACT_365 modelDC = new DayCountConvention_ACT_365();
		for(int i=0; i<atmNormalVolatilities.length; i++ ) {

			final LocalDate exerciseDate = cal.getDateFromDateAndOffsetCode(referenceDate, atmExpiries[i]);
			final LocalDate tenorEndDate = cal.getDateFromDateAndOffsetCode(exerciseDate, atmTenors[i]);
			double	exercise		= modelDC.getDaycountFraction(referenceDate, exerciseDate);
			double	tenor			= modelDC.getDaycountFraction(exerciseDate, tenorEndDate);

			// We consider an idealized tenor grid (alternative: adapt the model grid)
			exercise	= Math.round(exercise/0.25)*0.25;
			tenor		= Math.round(tenor/0.25)*0.25;

			if(exercise < 1.0) {
				continue;
			}

			final int numberOfPeriods = (int)Math.round(tenor / swapPeriodLength);

			//in this case swaptions are at the money
			final double	moneyness			= 0.0;
			final double	targetVolatility	= atmNormalVolatilities[i];
			
			//This is the convention of quotation
			final String	targetVolatilityType = "VOLATILITYNORMAL";
			//The weight assigned of each product
			final double	weight = 1.0;

			//A collection of instrument on which the calibration will be done
			calibrationProducts.add(createCalibrationItem(weight, exercise, swapPeriodLength,
					numberOfPeriods, moneyness, targetVolatility, targetVolatilityType, 
					forwardCurve, discountCurve));
			calibrationItemNames.add(atmExpiries[i]+"\t"+atmTenors[i]);
		}
		
		final double lastTime	= 40.0;
		final double dt		= 0.25;
		final TimeDiscretizationFromArray timeDiscretizationFromArray = new TimeDiscretizationFromArray(0.0, (int) (lastTime / dt), dt);//this is the timediscretization of t
		final TimeDiscretization euriborPeriodDiscretization = timeDiscretizationFromArray; //This is the timediscretization of T

		final TimeDiscretization volatilityDiscretization = new TimeDiscretizationFromArray(new double[] {0, 1 ,2, 3, 5, 7, 10, 15, 20, 30}); //this is the scalings of volatility
		final RandomVariableFromArrayFactory randomVariableFromArrayFactory = new RandomVariableFromArrayFactory();

		//Create a piecewise constant volatility
		final AbstractShortRateVolatilityModel volatilityModel = new ShortRateVolatilityModelPiecewiseConstant(randomVariableFromArrayFactory, timeDiscretizationFromArray, volatilityDiscretization, new double[] {0.02}, new double[] {0.1}, true);

		final BrownianMotion brownianMotion = new BrownianMotionFromMersenneRandomNumbers(timeDiscretizationFromArray, 2 /* numberOfFactors */, numberOfPaths, 3141 /* seed */);

		//		//Create map (mainly use calibration defaults)
		final Map<String, Object> properties = new HashMap<>();
		final Map<String, Object> calibrationParameters = new HashMap<>();
		calibrationParameters.put("brownianMotion", brownianMotion);
		properties.put("calibrationParameters", calibrationParameters);		//here recall the swaption
		
		final CalibrationProduct[] calibrationItemsHW = new CalibrationProduct[calibrationItemNames.size()];
		for(int i=0; i<calibrationItemNames.size(); i++) {
			calibrationItemsHW[i] = new CalibrationProduct(calibrationProducts.get(i).getProduct(),calibrationProducts.get(i).getTargetValue(),calibrationProducts.get(i).getWeight());
		}
		
		
		/*
		 * The model is calibrated when we instantiate it.
		 * Notice that we send to the constructor the list of calibration products.
		 */
		
		final HullWhiteModelWithShiftExtension hullWhiteModelWithShiftExtension = HullWhiteModelWithShiftExtension.of(randomVariableFromArrayFactory, euriborPeriodDiscretization, 
				curveModel, forwardCurve, discountCurve, volatilityModel, calibrationItemsHW, properties);
		
		System.out.println("\nCalibrated parameters are:");
		final double[] param = ((ShortRateVolatilityModelParametric) hullWhiteModelWithShiftExtension.getVolatilityModel()).getParameterAsDouble();
		for (final double p : param) {
			System.out.println(p);
		}
		
		/*
		 * Once we have a calibrated model, we can use it to evaluate products.
		 * 
		 * Here we use a Monte Carlo simulation to test if the prices produced by the model
		 * are close to the market prices.
		 * 
		 */
		final EulerSchemeFromProcessModel process = new EulerSchemeFromProcessModel(hullWhiteModelWithShiftExtension, brownianMotion, EulerSchemeFromProcessModel.Scheme.EULER);
		final TermStructureMonteCarloSimulationFromTermStructureModel hullWhiteModelSimulation = 
				new TermStructureMonteCarloSimulationFromTermStructureModel(hullWhiteModelWithShiftExtension, process);
		
		System.out.println("\nValuation on calibrated model:");
		
		final DecimalFormat formatterValue		= new DecimalFormat(" #0.0000000;-#0.0000000");
		
		for (int i = 0; i < calibrationProducts.size(); i++) {
			final AbstractTermStructureMonteCarloProduct calibrationProduct = calibrationProducts.get(i).getProduct();
			try {
				final double valueModel = calibrationProduct.getValue(hullWhiteModelSimulation);
				final double valueTarget = calibrationProducts.get(i).getTargetValue().getAverage();
				final double error = valueModel-valueTarget;
				System.out.println(calibrationItemNames.get(i) + "\t" +
				"Model: " + formatterValue.format(valueModel) + 
				"\t Target: " + formatterValue.format(valueTarget)  +
				"\t Deviation: " + (error));// + "\t" + calibrationProduct.toString());
			}
			catch(final Exception e) {
			}
		}
		
		
		
		return hullWhiteModelWithShiftExtension;	

	}
	
	/*
	 * A helper method that computes par swap rates.
	 * This is needed in order to define the ATM strike of swaptions.
	 */
	//This method calculate the K^IRS
	public static double getParSwaprate(final ForwardCurve forwardCurve, final DiscountCurve discountCurve, final double[] swapTenor) {
		return net.finmath.marketdata.products.Swap.getForwardSwapRate(
				new TimeDiscretizationFromArray(swapTenor), 
				new TimeDiscretizationFromArray(swapTenor), forwardCurve, discountCurve);
	}
	
	/*
	 * This is a helper method that automates the creation of
	 * financial products whose quotes we would like to fit with our model:
	 * The market observed and the model produced prices of these products should
	 * be as close as possible.
	 */
	public static CalibrationProduct createCalibrationItem(final double weight, 
			final double exerciseDate, final double swapPeriodLength, 
			final int numberOfPeriods, final double moneyness, 
			final double targetVolatility, final String targetVolatilityType, 
			final ForwardCurve forwardCurve, final DiscountCurve discountCurve) 
					throws CalculationException {

		final double[]	fixingDates			= new double[numberOfPeriods];
		final double[]	paymentDates		= new double[numberOfPeriods];
		final double[]	swapTenor			= new double[numberOfPeriods + 1];

		for (int periodStartIndex = 0; periodStartIndex < numberOfPeriods; periodStartIndex++) {
			fixingDates[periodStartIndex] = exerciseDate + periodStartIndex * swapPeriodLength;
			paymentDates[periodStartIndex] = exerciseDate + (periodStartIndex + 1) * swapPeriodLength;
			swapTenor[periodStartIndex] = exerciseDate + periodStartIndex * swapPeriodLength;
		}
		swapTenor[numberOfPeriods] = exerciseDate + numberOfPeriods * swapPeriodLength;

		// Swaptions swap rate
		final double swaprate = moneyness + getParSwaprate(forwardCurve, discountCurve, swapTenor);

		// Set swap rates for each period
		final double[] swaprates = new double[numberOfPeriods];
		Arrays.fill(swaprates, swaprate);

		/*
		 * We use Monte-Carlo calibration on implied volatility.
		 * Alternatively you may change here to Monte-Carlo valuation on price or
		 * use an analytic approximation formula, etc.
		 * You will need to add a method that computes the swap annuity.
		 */
		final SwaptionSimple swaptionMonteCarlo = new SwaptionSimple(swaprate, swapTenor,
				SwaptionSimple.ValueUnit.valueOf(targetVolatilityType));
		
		 //double targetValuePrice = AnalyticFormulas.blackModelSwaptionValue(swaprate, 
		 //targetVolatility, fixingDates[0], swaprate, getSwapAnnuity(discountCurve, swapTenor));
		 
		//Create a swaption on which the calibration will be done
		return new CalibrationProduct(swaptionMonteCarlo, targetVolatility, weight);
	}
	
	
}
