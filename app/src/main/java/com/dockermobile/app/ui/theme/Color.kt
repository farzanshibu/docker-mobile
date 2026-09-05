package com.dockermobile.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic palette in the shape Apple's HIG asks for: every token has a light
 * and a dark variant, foreground colours are brighter in dark rather than a
 * naive inversion, and each colour carries exactly one meaning.
 *
 * Contrast ratios in the comments are measured against the surface the colour
 * is actually used on (white / #1C1C1E), and clear the 4.5:1 minimum for body
 * text called out in the accessibility guidelines.
 */

// ------------------------------------------------------------------ accent
// Docker blue is the brand accent; dark mode gets a brightened variant so it
// keeps its contrast against a near-black background.
val AccentLight = Color(0xFF1D63ED) // 5.3:1 on white
val AccentDark = Color(0xFF4C8DFF)  // 5.2:1 on #1C1C1E
val OnAccent = Color(0xFFFFFFFF)

// ------------------------------------------------------------- backgrounds
// Two levels, matching the iOS grouped-content model: the base plane the app
// sits on, and the elevated plane cards/rows are drawn on.
val BaseLight = Color(0xFFF2F2F7)
val ElevatedLight = Color(0xFFFFFFFF)
val Elevated2Light = Color(0xFFEDEDF2)

val BaseDark = Color(0xFF000000)
val ElevatedDark = Color(0xFF1C1C1E)
val Elevated2Dark = Color(0xFF2C2C2E)

// ------------------------------------------------------------------ labels
val LabelLight = Color(0xFF000000)
val LabelSecondaryLight = Color(0x993C3C43)  // 60%
val LabelTertiaryLight = Color(0x4D3C3C43)   // 30%

val LabelDark = Color(0xFFFFFFFF)
val LabelSecondaryDark = Color(0x99EBEBF5)
val LabelTertiaryDark = Color(0x4DEBEBF5)

// -------------------------------------------------------- separators/fills
val SeparatorLight = Color(0xFFC6C6C8)
val SeparatorDark = Color(0xFF38383A)

val FillLight = Color(0x1F787880)   // ~12% — segmented-control troughs, chips
val FillDark = Color(0x3D787880)    // ~24%

// The sliding knob of a segmented control has to read as raised above the
// trough, which means lighter in dark mode rather than darker.
val KnobLight = Color(0xFFFFFFFF)
val KnobDark = Color(0xFF636366)

// ------------------------------------------------------------------ status
// One colour per state, never reused for anything else. Light variants are
// darkened so they stay legible as text on a white row.
val StatusRunningLight = Color(0xFF1E7A33) // 5.4:1 on white
val StatusRunningDark = Color(0xFF30D158)  // 8.4:1 on #1C1C1E
val StatusWarnLight = Color(0xFFB25000)    // 5.2:1
val StatusWarnDark = Color(0xFFFF9F0A)
val StatusErrorLight = Color(0xFFD70015)   // 5.4:1
val StatusErrorDark = Color(0xFFFF453A)
val StatusNeutralLight = Color(0xFF6C6C70) // 5.2:1
val StatusNeutralDark = Color(0xFF98989F)  // 5.9:1
