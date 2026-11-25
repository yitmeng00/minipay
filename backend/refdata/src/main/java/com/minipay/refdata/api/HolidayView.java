package com.minipay.refdata.api;

import java.time.LocalDate;

public record HolidayView(String calendarCode, LocalDate date, String name, String releaseVersion) {
}
