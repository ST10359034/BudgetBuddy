package com.example.budgetbuddy.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.budgetbuddy.data.db.pojo.CategorySpending
import com.example.budgetbuddy.data.db.entity.ExpenseEntity
import com.example.budgetbuddy.data.repository.ExpenseRepository
import com.example.budgetbuddy.data.repository.BudgetRepository
import com.example.budgetbuddy.util.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.utils.ColorTemplate
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import android.graphics.Color
import java.text.NumberFormat

// Define Enums
enum class Period {
    DAILY, WEEKLY, MONTHLY // Add YEARLY etc. if needed
}

enum class CategoryDisplayMode {
   PERCENTAGE, AMOUNT
}

// Define ChartData or import if defined elsewhere
// This is a placeholder, adapt to your actual chart library needs
data class ChartData(
    val entries: List<Any>, // Use specific chart entry type (PieEntry, BarEntry)
    val labels: List<String>? = null,
    val colors: List<Int>? = null
)

// Define ReportsUiState matching the data needed by the Fragment
data class ReportsUiState(
    val isLoading: Boolean = true,
    val categoryDisplayMode: CategoryDisplayMode = CategoryDisplayMode.PERCENTAGE, // Add display mode state
    val totalSpending: BigDecimal = BigDecimal.ZERO,
    val averageBudget: BigDecimal = BigDecimal.ZERO, // Example field
    val spendingByCategoryChart: ChartData? = null, // Holds PieChart data // Potentially remove if pieChartData used directly
    val spendingTrendChart: ChartData? = null, // Holds BarChart data // Potentially remove if barChartData used directly
    val error: String? = null,
    // Fields from the older version that might be needed by the current Fragment implementation:
    val selectedDate: Calendar = Calendar.getInstance(),
    val selectedMonthYearText: String = "",
    val spendingChangeText: String = "",
    val pieChartData: List<PieEntry> = emptyList(),
    val pieChartColors: List<Int> = emptyList(),
    val categorySpendingData: List<Pair<String, BigDecimal>> = emptyList(), // Store raw category amounts
    val pieChartLegend: List<Pair<String, String>> = emptyList(), // Formatted legend for display
    val barChartData: Pair<List<BarEntry>, List<String>>? = null
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val budgetRepository: BudgetRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReportsUiState())
    val uiState: StateFlow<ReportsUiState> = _uiState.asStateFlow()

    private fun getCurrentUserId(): Long = sessionManager.getUserId()

    init {
        // Load data with hardcoded WEEKLY period
        loadReportData(Period.WEEKLY)
    }

    private fun loadReportData(period: Period = Period.WEEKLY) { // Default and enforce WEEKLY
        val userId = getCurrentUserId()
        if (userId == SessionManager.NO_USER_LOGGED_IN) {
            Log.e("ReportsViewModel", "Cannot load data, no user logged in")
            _uiState.value = ReportsUiState(isLoading = false, error = "Please log in.")
            return
        }

        // Use Period.WEEKLY explicitly
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                // Always get dates for WEEKLY
                val (startDate, endDate) = getDatesForPeriod(Period.WEEKLY)

                // Use correct repository methods
                // Note: BudgetRepository doesn't have an average budget flow, we fetch the specific month's budget
                val monthYearFormat = SimpleDateFormat("yyyy-MM", Locale.getDefault())
                val currentMonthYear = monthYearFormat.format(startDate) // Use start date to determine month

                combine(
                    expenseRepository.getExpensesBetween(userId, startDate, endDate),
                    expenseRepository.getSpendingByCategoryBetween(userId, startDate, endDate),
                    budgetRepository.getBudgetForMonth(userId, currentMonthYear) // Get budget for the relevant month
                ) { expenses, categorySpendingList, budgetForMonth ->

                    val totalSpending = expenses.sumOf { it.amount }
                    val avgBudget = budgetForMonth?.totalAmount ?: BigDecimal.ZERO // Use the fetched budget
                    val (pieEntries, rawCategoryData) = processCategoryData(categorySpendingList, totalSpending)
                    // Always process bar chart data for WEEKLY
                    val barData = processBarChartData(expenses, startDate, endDate, Period.WEEKLY)

                    // Populate the state object correctly
                    ReportsUiState(
                        isLoading = false,
                        categoryDisplayMode = _uiState.value.categoryDisplayMode, // Keep current mode
                        totalSpending = totalSpending,
                        averageBudget = avgBudget, // Populate with actual monthly budget
                        // Populate the fields used by the current Fragment implementation:
                        selectedDate = Calendar.getInstance().apply{ time = startDate }, // Reflect start of period
                        selectedMonthYearText = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(startDate),
                        spendingChangeText = "", // TODO: Recalculate if needed
                        pieChartData = pieEntries,
                        pieChartColors = generateGreyShades(pieEntries.size), // Generate grey shades
                        categorySpendingData = rawCategoryData, // Store the raw data
                        pieChartLegend = formatLegendData(rawCategoryData, _uiState.value.categoryDisplayMode, totalSpending),
                        barChartData = barData,
                        error = null
                        // Remove spendingByCategoryChart, spendingTrendChart if replaced by specific fields
                    )
                }.catch { e: Throwable ->
                    Log.e("ReportsViewModel", "Error combining flows for user $userId, period $period", e)
                    _uiState.update { it.copy(isLoading = false, error = "Failed to load report data.") }
                }.collect { newState: ReportsUiState ->
                    _uiState.value = newState
                }
            } catch (e: Exception) {
                Log.e("ReportsViewModel", "Error in loadReportData launch block", e)
                _uiState.update { it.copy(isLoading = false, error = "An unexpected error occurred.") }
            }
        }
    }

    private fun getDatesForPeriod(period: Period, baseCalendar: Calendar? = null): Pair<Date, Date> {
        val calendar = baseCalendar?.clone() as Calendar? ?: Calendar.getInstance()
        val endDate: Date
        val startDate: Date

        when (period) {
            Period.MONTHLY -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                setCalendarToStartOfDay(calendar)
                startDate = calendar.time
                calendar.set(Calendar.DAY_OF_MONTH, calendar.getActualMaximum(Calendar.DAY_OF_MONTH))
                setCalendarToEndOfDay(calendar)
                endDate = calendar.time
            }
            Period.WEEKLY -> {
                calendar.firstDayOfWeek = Calendar.SUNDAY // Or Monday
                calendar.set(Calendar.DAY_OF_WEEK, calendar.firstDayOfWeek)
                setCalendarToStartOfDay(calendar)
                startDate = calendar.time
                calendar.add(Calendar.DAY_OF_WEEK, 6)
                setCalendarToEndOfDay(calendar)
                endDate = calendar.time
            }
            Period.DAILY -> {
                 setCalendarToStartOfDay(calendar)
                 startDate = calendar.time
                 setCalendarToEndOfDay(calendar)
                 endDate = calendar.time
            }
        }
        return Pair(startDate, endDate)
    }

    private fun processCategoryData(categorySpendingList: List<CategorySpending>, totalSpending: BigDecimal): Pair<List<PieEntry>, List<Pair<String, BigDecimal>>> {
        if (totalSpending <= BigDecimal.ZERO || categorySpendingList.isEmpty()) {
            return Pair(emptyList(), emptyList()) // Return empty for both
        }
        val pieEntries = mutableListOf<PieEntry>()
        val categoryData = mutableListOf<Pair<String, BigDecimal>>()
        categorySpendingList.forEach { categorySpending ->
            val percentage = (categorySpending.total.divide(totalSpending, 4, RoundingMode.HALF_UP) * BigDecimal(100))
            val percentageFloat = percentage.toFloat()
            if (percentageFloat > 0.5) { // Threshold to avoid tiny slices
                pieEntries.add(PieEntry(percentageFloat, categorySpending.categoryName))
                categoryData.add(categorySpending.categoryName to categorySpending.total) // Store raw amount
            }
        }
        categoryData.sortByDescending { it.second } // Sort by amount
        return Pair(pieEntries, categoryData)
    }

    private fun processBarChartData(dailyExpensesList: List<ExpenseEntity>, startDate: Date, endDate: Date, period: Period): Pair<List<BarEntry>, List<String>> {
        val entries = ArrayList<BarEntry>()
        val labels = ArrayList<String>()
        val calendar = Calendar.getInstance()

        when (period) {
            Period.MONTHLY -> {
                calendar.time = startDate
                val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
                val dailyTotals = Array(daysInMonth + 1) { BigDecimal.ZERO } // Index 1 = Day 1
                val expenseCalendar = Calendar.getInstance()
                dailyExpensesList.forEach { expense ->
                    expenseCalendar.time = expense.date
                    val dayOfMonth = expenseCalendar.get(Calendar.DAY_OF_MONTH)
                    if (dayOfMonth in 1..daysInMonth) {
                        dailyTotals[dayOfMonth] += expense.amount
                    }
                }
                for (day in 1..daysInMonth) {
                    entries.add(BarEntry(day.toFloat(), dailyTotals[day].toFloat()))
                    labels.add(if (daysInMonth <= 10 || day % 5 == 0 || day == 1 || day == daysInMonth) day.toString() else "") // Smart labeling
                }
            }
            // TODO: Implement similar logic for WEEKLY and DAILY periods if needed
            Period.WEEKLY, Period.DAILY -> {
                  // Example: Logic for weekly (adapt as needed)
                  val weeklyTotals = Array(7) { BigDecimal.ZERO } // 0=Sun, 1=Mon...
                  val dayFormat = SimpleDateFormat("E", Locale.getDefault()) // Short day name
                  calendar.time = startDate
                  for(i in 0..6) {
                       labels.add(dayFormat.format(calendar.time))
                       calendar.add(Calendar.DATE, 1)
                  }
                  val expenseCalendar = Calendar.getInstance()
                   dailyExpensesList.forEach { expense ->
                       expenseCalendar.time = expense.date
                       // Map expense date to correct index (0-6) based on start date
                       val diff = (expense.date.time - startDate.time)
                       val dayIndex = (diff / (1000 * 60 * 60 * 24)).toInt()
                       if(dayIndex in 0..6) {
                           weeklyTotals[dayIndex] += expense.amount
                       }
                   }
                   for(i in 0..6) {
                        entries.add(BarEntry(i.toFloat(), weeklyTotals[i].toFloat()))
                   }
             }
        }
        return Pair(entries, labels)
    }

    // Helper to set calendar to start of the day
    private fun setCalendarToStartOfDay(calendar: Calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
    }

    // Helper to set calendar to end of the day
    private fun setCalendarToEndOfDay(calendar: Calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
    }

    fun changeMonth(amount: Int) {
        val currentCalendar = _uiState.value.selectedDate.clone() as Calendar
        currentCalendar.add(Calendar.MONTH, amount)
        loadReportDataForDate(currentCalendar)
    }

    private fun loadReportDataForDate(calendar: Calendar) {
        val userId = getCurrentUserId()
        if (userId == SessionManager.NO_USER_LOGGED_IN) {
            _uiState.update { it.copy(isLoading = false, error = "Please log in.") }
            return
        }
        // Note: This function inherently deals with monthly navigation.
        // We will still fetch weekly data *for the week containing the start of the navigated-to month*.
        // If the user expects the *week* to change when navigating months, this needs more complex logic.
        _uiState.update { it.copy(isLoading = true, selectedDate = calendar, error = null) }
        viewModelScope.launch {
            try {
                // Calculate dates based on the passed calendar (for month text)
                val dateFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                val selectedMonthYearText = dateFormat.format(calendar.time)

                // Get dates for the *week* starting at the beginning of the selected month
                // This might be slightly counter-intuitive if navigating months but keeping weekly view
                val weekCalendar = calendar.clone() as Calendar
                weekCalendar.set(Calendar.DAY_OF_MONTH, 1) // Go to start of month
                val (startOfWeek, endOfWeek) = getDatesForPeriod(Period.WEEKLY, weekCalendar) // Get week for start of month

                // Fetch previous month's spending for comparison (still based on month)
                val previousMonthCalendar = calendar.clone() as Calendar
                previousMonthCalendar.add(Calendar.MONTH, -1)
                val startOfPreviousMonth = getStartOfMonth(previousMonthCalendar)
                val endOfPreviousMonth = getEndOfMonth(previousMonthCalendar)

                // Fetch data using the calculated WEEKLY range for expenses/categories/bar chart
                val spendingSelectedWeekDeferred = async { expenseRepository.getTotalSpendingBetween(userId, startOfWeek, endOfWeek).first() }
                val spendingPreviousMonthDeferred = async { expenseRepository.getTotalSpendingBetween(userId, startOfPreviousMonth, endOfPreviousMonth).first() }
                val categorySpendingDeferred = async { expenseRepository.getSpendingByCategoryBetween(userId, startOfWeek, endOfWeek).first() }
                val weeklyExpensesDeferred = async { expenseRepository.getExpensesBetween(userId, startOfWeek, endOfWeek).first() }

                // Await results
                val totalSpendingSelected = spendingSelectedWeekDeferred.await() ?: BigDecimal.ZERO
                val totalSpendingPrevious = spendingPreviousMonthDeferred.await() ?: BigDecimal.ZERO
                val categorySpendingList = categorySpendingDeferred.await()
                val weeklyExpensesList = weeklyExpensesDeferred.await()

                // Process data
                val spendingChangeText = calculateSpendingChange(totalSpendingSelected, totalSpendingPrevious) // Still compares selected week to previous month
                val (pieChartData, rawCategoryData) = processCategoryData(categorySpendingList, totalSpendingSelected)
                val pieChartLegend = formatLegendData(rawCategoryData, _uiState.value.categoryDisplayMode, totalSpendingSelected)
                // Always process bar chart for WEEKLY
                val barChartData = processBarChartData(weeklyExpensesList, startOfWeek, endOfWeek, Period.WEEKLY)
                val pieChartColors = generateGreyShades(pieChartData.size)

                // Update state with all necessary fields
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        selectedDate = calendar, // Keep the calendar representing the month for display
                        selectedMonthYearText = selectedMonthYearText,
                        totalSpending = totalSpendingSelected, // Reflects weekly total
                        spendingChangeText = spendingChangeText, // Reflects week vs previous month
                        pieChartData = pieChartData,
                        pieChartColors = pieChartColors,
                        categorySpendingData = rawCategoryData,
                        pieChartLegend = pieChartLegend,
                        barChartData = barChartData,
                        error = null
                    )
                }
            } catch (e: Exception) {
                Log.e("ReportsViewModel", "Error in loadReportDataForDate", e)
                _uiState.update { it.copy(isLoading = false, error = "Failed to load specific month report data.") }
            }
        }
    }

    private fun calculateSpendingChange(currentMonthSpending: BigDecimal, previousMonthSpending: BigDecimal): String {
        if (previousMonthSpending <= BigDecimal.ZERO) {
            return if (currentMonthSpending > BigDecimal.ZERO) "↑ from last month" else "" // No previous data or increase from zero
        }
        val change = ((currentMonthSpending - previousMonthSpending).divide(previousMonthSpending, 4, RoundingMode.HALF_UP) * BigDecimal(100))
        val percentage = change.setScale(1, RoundingMode.HALF_UP)
        return when {
            percentage > BigDecimal.ZERO -> "↑ $percentage% from last month"
            percentage < BigDecimal.ZERO -> "↓ ${percentage.abs()}% from last month"
            else -> "↔ No change from last month"
        }
    }

    // --- Date Helper Functions (adjust for specific calendar) ---
    private fun getStartOfMonth(calendar: Calendar): Date {
        val cal = calendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.time
    }

    private fun getEndOfMonth(calendar: Calendar): Date {
         val cal = calendar.clone() as Calendar
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return cal.time
    }

    fun refreshData() {
        loadReportData(Period.WEEKLY)
    }

    private fun generateGreyShades(count: Int): List<Int> {
        if (count <= 0) return emptyList()

        val shades = mutableListOf<Int>()
        // Define base shades (darkest to lightest)
        val baseGreys = listOf(
            Color.rgb(50, 50, 50), // Very Dark Grey
            Color.DKGRAY,
            Color.GRAY,
            Color.LTGRAY,
            Color.rgb(220, 220, 220) // Very Light Grey
        )

        for (i in 0 until count) {
            // Cycle through base greys
            shades.add(baseGreys[i % baseGreys.size])
            // Optional: Could add interpolation or slight variation if more unique shades are needed
        }
        return shades
    }

    // --- Report Generation ---
    fun generateReportContent(): String? {
        val state = _uiState.value
        if (state.isLoading || state.error != null) {
            Log.w("ReportsViewModel", "Cannot generate report while loading or in error state.")
            return null
        }

        val builder = StringBuilder()
        builder.appendLine("Budget Buddy Report")
        builder.appendLine("Period: ${state.selectedMonthYearText}")
        builder.appendLine("=====================================")
        builder.appendLine("Total Spending: ${formatCurrency(state.totalSpending)}")
        // Optional: Add spending change info
        if (state.spendingChangeText.isNotEmpty()) {
             builder.appendLine("Change: ${state.spendingChangeText}")
        }
        builder.appendLine()
        builder.appendLine("Spending by Category:")
        builder.appendLine("-------------------------------------")

        if (state.pieChartLegend.isEmpty()) {
            builder.appendLine("No category spending data for this period.")
        } else {
            state.pieChartLegend.forEach { (category, percentage) ->
                // Find corresponding spending amount (requires calculation or storing it in state)
                // For simplicity, let's just use the percentage for now.
                // To show amount, you'd need to iterate state.pieChartData and match label.
                builder.appendLine("- $category: $percentage")
            }
        }

        // TODO: Add daily spending breakdown from bar chart data if desired

        builder.appendLine()
        builder.appendLine("=====================================")
        builder.appendLine("Generated on: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")

        return builder.toString()
    }

    // Helper function to format currency (already exists potentially)
    private fun formatCurrency(amount: BigDecimal): String {
        return NumberFormat.getCurrencyInstance(Locale.getDefault()).format(amount)
    }

    fun toggleCategoryDisplayMode() {
        _uiState.update { currentState ->
            val newMode = if (currentState.categoryDisplayMode == CategoryDisplayMode.PERCENTAGE) {
                CategoryDisplayMode.AMOUNT
            } else {
                CategoryDisplayMode.PERCENTAGE
            }

            // Use the stored raw data to recalculate the legend for the new mode
            val updatedLegend = formatLegendData(
                categoryData = currentState.categorySpendingData, // Use stored raw data
                mode = newMode,
                totalSpending = currentState.totalSpending
            )

            currentState.copy(
                categoryDisplayMode = newMode,
                pieChartLegend = updatedLegend // Update the legend in the state
            )
        }
    }

    // Add or adjust this helper function if it doesn't exist or needs modification
    // It should take raw category spending data (Pair<String, BigDecimal>) and format it
    private fun formatLegendData(
        categoryData: List<Pair<String, BigDecimal>>, // Expects raw category names and amounts
        mode: CategoryDisplayMode,
        totalSpending: BigDecimal
    ): List<Pair<String, String>> {
        val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
        return categoryData.map { (categoryName, amount) ->
            val formattedValue = when (mode) {
                CategoryDisplayMode.PERCENTAGE -> {
                    if (totalSpending > BigDecimal.ZERO) {
                        val percentage = (amount.divide(totalSpending, 4, RoundingMode.HALF_UP) * BigDecimal(100))
                            .setScale(1, RoundingMode.HALF_UP)
                        "$percentage%"
                    } else {
                        "0.0%"
                    }
                }
                CategoryDisplayMode.AMOUNT -> formatter.format(amount)
            }
            categoryName to formattedValue
        }
    }
}