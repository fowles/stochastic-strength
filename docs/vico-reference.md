# Vico 3.2.3 Reference (compose-m3)

Source: `vendor/vico/` (gitignored, checked out at tag `v3.2.3`). Always grep/read there
first before fetching web docs. Live examples: the chart files under
`app/…/ui/` (`ExerciseDetailScreen.kt`, `debug/components/DebugLineChart.kt`,
`ExerciseProgressionChart.kt`, `components/ChartRange.kt`).

Vico 3 collapsed the old `core` module into the KMP `compose` module — **everything is
now under `com.patrykandpatrick.vico.compose.*`** and `compose-m3` pulls it all in. If you
are porting 2.x code or an old snippet, see [Migrating from 2.x](#migrating-from-2x) at the
bottom.

---

## Package map

| What you need | Import from |
|---|---|
| `CartesianChartHost`, `rememberCartesianChart` | `com.patrykandpatrick.vico.compose.cartesian` |
| `rememberVicoScrollState`, `Scroll`, `AutoScrollCondition` | `com.patrykandpatrick.vico.compose.cartesian` |
| `rememberLineCartesianLayer`, `rememberColumnCartesianLayer`, `LineCartesianLayer`, `ColumnCartesianLayer` | `com.patrykandpatrick.vico.compose.cartesian.layer` |
| `rememberLine` (companion ext), `rememberLineCartesianLayer` | `com.patrykandpatrick.vico.compose.cartesian.layer` |
| `HorizontalAxis`, `VerticalAxis` (axis factories are **companion members**) | `com.patrykandpatrick.vico.compose.cartesian.axis` |
| `rememberAxisGuidelineComponent`, `rememberAxisLineComponent`, `rememberAxisLabelComponent`, `rememberAxisTickComponent` | `com.patrykandpatrick.vico.compose.cartesian.axis` |
| `rememberDefaultCartesianMarker`, `DefaultCartesianMarker`, `CartesianMarker`, `CartesianMarkerVisibilityListener`, `LineCartesianLayerMarkerTarget` | `com.patrykandpatrick.vico.compose.cartesian.marker` |
| `CartesianChartModelProducer`, `lineModel`/`lineSeries`, `columnModel`/`columnSeries` | `com.patrykandpatrick.vico.compose.cartesian.data` |
| `CartesianValueFormatter`, `CartesianLayerRangeProvider`, `LineCartesianLayerModel` | `com.patrykandpatrick.vico.compose.cartesian.data` |
| `rememberTextComponent`, `rememberLineComponent`, `rememberShapeComponent`, `ShapeComponent`, `LayeredComponent` | `com.patrykandpatrick.vico.compose.common.component` |
| `Fill`, `Insets`, `MarkerCornerBasedShape` | `com.patrykandpatrick.vico.compose.common` |
| `vicoTheme`, `ProvideVicoTheme`, `rememberHorizontalLegend`, `rememberVerticalLegend`, `LegendItem` | `com.patrykandpatrick.vico.compose.common` |
| `ExtraStore`, `MutableExtraStore` | `com.patrykandpatrick.vico.compose.common.data` |
| `rememberM3VicoTheme` | `com.patrykandpatrick.vico.compose.m3.common` |
| `Shape`, `RectangleShape`, shapes | `androidx.compose.ui.graphics` / `androidx.compose.foundation.shape` (**Compose-native — Vico has no `CorneredShape`**) |

---

## Minimal line chart (current project pattern)

```kotlin
// source: app/…/ExerciseDetailScreen.kt
val modelProducer = remember { CartesianChartModelProducer() }

LaunchedEffect(points) {
    modelProducer.runTransaction {
        lineModel {                                  // was `lineSeries {` in 2.x
            series(x = points.map { it.xValue }, y = points.map { it.yValue })
        }
    }
}

CartesianChartHost(
    chart = rememberCartesianChart(rememberLineCartesianLayer()),
    modelProducer = modelProducer,
    modifier = modifier,
)
```

---

## CartesianChartHost

Source: `vendor/vico/vico/compose/src/…/cartesian/CartesianChartHost.kt`

```kotlin
CartesianChartHost(
    chart: CartesianChart,
    modelProducer: CartesianChartModelProducer,
    modifier: Modifier = Modifier,
    scrollState: VicoScrollState = rememberVicoScrollState(),
    zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
    animationSpec: AnimationSpec<Float>? = defaultCartesianDiffAnimationSpec,
    animateIn: Boolean = true,
    placeholder: @Composable BoxScope.() -> Unit = {},
)
```

Default height is `CHART_HEIGHT.dp` (256 dp). Override with `modifier = Modifier.height(200.dp)`.

Static-data overload: replace `modelProducer` with `model: CartesianChartModel`.

---

## rememberCartesianChart

Source: `vendor/vico/vico/compose/src/…/cartesian/CartesianChart.kt`

```kotlin
rememberCartesianChart(
    vararg layers: CartesianLayer<*>,        // rememberLineCartesianLayer(), rememberColumnCartesianLayer()
    startAxis: Axis<Axis.Position.Vertical.Start>? = null,     // VerticalAxis.rememberStart(…)
    topAxis: Axis<Axis.Position.Horizontal.Top>? = null,
    endAxis: Axis<Axis.Position.Vertical.End>? = null,
    bottomAxis: Axis<Axis.Position.Horizontal.Bottom>? = null, // HorizontalAxis.rememberBottom(…)
    marker: CartesianMarker? = null,
    markerVisibilityListener: CartesianMarkerVisibilityListener? = null,
    layerPadding: (ExtraStore) -> CartesianLayerPadding = { CartesianLayerPadding() },
    legend: Legend<CartesianMeasuringContext, CartesianDrawingContext>? = null,
    fadingEdges: FadingEdges? = null,
    decorations: List<Decoration> = emptyList(),
    persistentMarkers: (PersistentMarkerScope.(ExtraStore) -> Unit)? = null,
    getXStep: (CartesianChartModel, minX: Double, maxX: Double) -> Double = { model, minX, _ -> … },
)
```

`persistentMarkers` pins a marker at fixed x’s regardless of touch — e.g.
`persistentMarkers = { selectedDay?.let { marker at it.toDouble() } }`
(see `ExerciseProgressionChart.kt`).

---

## Line layer

Source: `vendor/vico/vico/compose/src/…/cartesian/layer/LineCartesianLayer.kt`

```kotlin
rememberLineCartesianLayer(
    lineProvider: LineCartesianLayer.LineProvider =
        LineCartesianLayer.LineProvider.series(
            vicoTheme.lineCartesianLayerColors.map { color ->
                LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(Fill(color)))
            }
        ),
    pointSpacing: Dp = Defaults.POINT_SPACING.dp,
    rangeProvider: CartesianLayerRangeProvider = remember { CartesianLayerRangeProvider.auto() },
    verticalAxisPosition: Axis.Position.Vertical? = null,
)
```

### Custom line styling

```kotlin
val myLine = LineCartesianLayer.rememberLine(
    fill = LineCartesianLayer.LineFill.single(Fill(MaterialTheme.colorScheme.primary)),
    stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),   // was `.continuous(…)`
    areaFill = LineCartesianLayer.AreaFill.single(                          // shaded area under the line
        Fill(Brush.verticalGradient(listOf(color.copy(alpha = 0.4f), Color.Transparent)))
    ),
    interpolator = LineCartesianLayer.Interpolator.cubic(),                 // smooth curve (see below)
)
rememberLineCartesianLayer(
    lineProvider = LineCartesianLayer.LineProvider.series(myLine)
)
```

- **Fill**: `Fill(Color)` for a solid color, `Fill(Brush)` for a gradient (Compose `Brush`).
- **Stroke**: `LineStroke.Continuous(thickness, cap)` or `LineStroke.Dashed(thickness, cap, dashLength, gapLength)`.
- **Interpolator** (replaces the deprecated `PointConnector`), passed as the `interpolator` arg:
  - `LineCartesianLayer.Interpolator.Sharp` — straight segments (default)
  - `LineCartesianLayer.Interpolator.cubic(curvature)` — smooth Bézier
  - `LineCartesianLayer.Interpolator.catmullRom()` — Catmull-Rom spline

### Points (dots)

```kotlin
LineCartesianLayer.rememberLine(
    fill = LineCartesianLayer.LineFill.single(Fill.Transparent),   // hide the line, show only dots
    pointProvider = LineCartesianLayer.PointProvider.single(
        LineCartesianLayer.Point(                                  // was `LineCartesianLayer.point(…)`
            rememberShapeComponent(Fill(color), CircleShape),
            size = 8.dp,
        )
    ),
)
```

### Multiple series (different colors)

```kotlin
// Each series() call in the transaction → one line; matched by index to lineProvider
LineCartesianLayer.LineProvider.series(line1, line2, line3)  // vararg (or a List)
```

---

## Column layer

Source: `vendor/vico/vico/compose/src/…/cartesian/layer/ColumnCartesianLayer.kt`

```kotlin
rememberColumnCartesianLayer(
    columnProvider: ColumnCartesianLayer.ColumnProvider =
        ColumnCartesianLayer.ColumnProvider.series(
            vicoTheme.columnCartesianLayerColors.map { color ->
                rememberLineComponent(Fill(color), Defaults.COLUMN_WIDTH.dp)
            }
        ),
    columnCollectionSpacing: Dp = Defaults.COLUMN_COLLECTION_SPACING.dp,
    mergeMode: (ExtraStore) -> ColumnCartesianLayer.MergeMode = { ColumnCartesianLayer.MergeMode.Grouped() },
    dataLabel: TextComponent? = null,
    rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.auto(),
    verticalAxisPosition: Axis.Position.Vertical? = null,
)
```

`MergeMode` is nested in `ColumnCartesianLayer` and is now types, not factory functions:
- `ColumnCartesianLayer.MergeMode.Grouped(columnSpacing)` — side by side (class)
- `ColumnCartesianLayer.MergeMode.Stacked` — stacked bars (object)

---

## Data: filling the model

Source: `vendor/vico/vico/compose/src/…/cartesian/data/LineCartesianLayerModel.kt` + `ColumnCartesianLayerModel.kt`

```kotlin
// suspend — must be called from a coroutine (e.g. inside LaunchedEffect)
modelProducer.runTransaction {
    lineModel {                                     // `lineSeries` still works but is deprecated
        series(x = listOf(1, 2, 3), y = listOf(10.0, 20.0, 15.0))
        series(y = listOf(5, 8, 12))                // indices used as x
        series(5, 8, 12)                            // vararg shorthand
        series(x, y, key = "own")                   // optional stable key (defaults to the series index)
    }
    // columnModel { … } same API
    // combo chart: add both lineModel and columnModel in the same transaction
    extras { store -> store[myKey] = someValue }    // attach arbitrary data
}
```

---

## Axes

Source: `vendor/vico/vico/compose/src/…/cartesian/axis/`. Factories are **companion members**
on the compose `HorizontalAxis` / `VerticalAxis` classes — call `VerticalAxis.rememberStart(…)`,
no separate `rememberStart` import.

### Bottom / Top (HorizontalAxis)

```kotlin
HorizontalAxis.rememberBottom(
    line: LineComponent? = rememberAxisLineComponent(),
    label: TextComponent? = rememberAxisLabelComponent(),
    labelRotationDegrees: Float = Defaults.AXIS_LABEL_ROTATION_DEGREES,
    valueFormatter: CartesianValueFormatter = remember { CartesianValueFormatter.decimal() },
    tick: LineComponent? = rememberAxisTickComponent(),
    tickLength: Dp = Defaults.AXIS_TICK_LENGTH.dp,
    guideline: LineComponent? = rememberAxisGuidelineComponent(),
    itemPlacer: HorizontalAxis.ItemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
    size: BaseAxis.Size = BaseAxis.Size.Auto(),
    titleComponent: TextComponent? = null,
    title: (ExtraStore) -> CharSequence? = { null },   // was a plain CharSequence? in 2.x
    tickPosition: HorizontalAxis.TickPosition = TickPosition.Outside,
    lineDrawingOrder: LineDrawingOrder = LineDrawingOrder.UnderLayers,
)
// rememberTop(…) — same params
```

`itemPlacer` options:
- `HorizontalAxis.ItemPlacer.aligned(spacing = 1, offset = 0)` — every Nth x value
- `HorizontalAxis.ItemPlacer.segmented()` — one label per column group

### Start / End (VerticalAxis)

```kotlin
VerticalAxis.rememberStart(
    line, label, labelRotationDegrees,
    horizontalLabelPosition: VerticalAxis.HorizontalLabelPosition = Outside,
    verticalLabelPosition: Position.Vertical = Position.Vertical.Center,
    valueFormatter = remember { CartesianValueFormatter.decimal() },
    tick, tickLength, guideline,
    itemPlacer: VerticalAxis.ItemPlacer = remember { VerticalAxis.ItemPlacer.step() },
    size, titleComponent,
    title: (ExtraStore) -> CharSequence? = { null },
    tickPosition, lineDrawingOrder,
)
// rememberEnd(…) — same params
```

`itemPlacer` options:
- `VerticalAxis.ItemPlacer.step { stepValue }` — every N units
- `VerticalAxis.ItemPlacer.count { count }` — target number of labels

---

## Value formatters

Source: `vendor/vico/vico/compose/src/…/cartesian/data/CartesianValueFormatter.kt`

```kotlin
// Built-ins (companion factories)
CartesianValueFormatter.decimal(/* DecimalFormat */)    // default formatter
CartesianValueFormatter.yPercent(decimalCount = 2)

// Custom (fun interface — (context, value, verticalAxisPosition))
val formatter = CartesianValueFormatter { _, value, _ ->
    SimpleDateFormat("MMM d", Locale.US).format(Date(value.toLong() * 86_400_000))
}
```

### Custom Y range

`CartesianLayerRangeProvider` lives at `…compose.cartesian.data` (see `ChartRange.kt`):

```kotlin
val rangeProvider = object : CartesianLayerRangeProvider {
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = floor(minY / 5.0) * 5.0
    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = ceil(maxY / 5.0) * 5.0
}
```

---

## Marker

Source: `vendor/vico/vico/compose/src/…/cartesian/marker/DefaultCartesianMarker.kt`
Sample: `vendor/vico/sample/charts/compose/src/…/Marker.kt`

```kotlin
rememberDefaultCartesianMarker(
    label: TextComponent,                    // required
    valueFormatter: DefaultCartesianMarker.ValueFormatter = remember { DefaultCartesianMarker.ValueFormatter.default() },
    labelPosition: DefaultCartesianMarker.LabelPosition = LabelPosition.Top,
    indicator: ((Color) -> Component)? = null,
    indicatorSize: Dp = Defaults.MARKER_INDICATOR_SIZE.dp,
    guideline: LineComponent? = null,
)
```

`LabelPosition`: `Top`, `Bottom`, `AroundPoint`, `AbovePoint`, `BelowPoint`.

`ValueFormatter` is `(context, targets: List<CartesianMarker.Target>) -> CharSequence`. For a
line chart the targets are `LineCartesianLayerMarkerTarget`, whose `points` each carry an
`entry` and a **Compose `Color`** (`point.color` — an ARGB `Int` in 2.x). See
`formatLineMarkerLabel` in `DebugLineChart.kt`.

Minimal styled marker (M3, tooltip bubble with a tail):

```kotlin
@Composable
fun rememberMarker(): CartesianMarker {
    val label = rememberTextComponent(
        style = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp),
        padding = Insets(8.dp, 4.dp),
        background = rememberShapeComponent(
            fill = Fill(MaterialTheme.colorScheme.surface),
            shape = MarkerCornerBasedShape(RoundedCornerShape(4.dp)),   // was markerCorneredShape(…)
            strokeFill = Fill(MaterialTheme.colorScheme.outline),
            strokeThickness = 1.dp,
        ),
    )
    return rememberDefaultCartesianMarker(
        label = label,
        guideline = rememberAxisGuidelineComponent(),
    )
}
```

Attach to chart: `rememberCartesianChart(…, marker = rememberMarker())`. To react to
selection, pass `markerVisibilityListener = object : CartesianMarkerVisibilityListener { … }`
(`onShown`/`onUpdated`/`onHidden` each receive `List<CartesianMarker.Target>`).

---

## M3 Theme

Source: `vendor/vico/vico/compose-m3/src/…/common/VicoTheme.kt`

```kotlin
// Use the M3 color scheme for line/column colors
ProvideVicoTheme(rememberM3VicoTheme()) {
    CartesianChartHost(…)
}

// Custom overrides
ProvideVicoTheme(
    rememberM3VicoTheme(
        columnCartesianLayerColors = listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.secondary,
        ),
    )
) { … }
```

`vicoTheme` (composable property) reads the current theme; e.g.
`vicoTheme.lineCartesianLayerColors[0]`.

---

## Components

Source: `vendor/vico/vico/compose/src/…/common/component/Components.kt`

```kotlin
rememberLineComponent(
    fill: Fill = Fill.Black,
    thickness: Dp = Defaults.LINE_COMPONENT_THICKNESS_DP.dp,
    shape: Shape = RectangleShape,                 // Compose Shape
    margins: Insets = Insets.Zero,
    strokeFill: Fill = Fill.Transparent,
    strokeThickness: Dp = 0.dp,
    shadows: List<Shadow> = emptyList(),           // was a single `shadow: Shadow?`
)

rememberShapeComponent(
    fill: Fill = Fill.Black,
    shape: Shape = RectangleShape,
    margins: Insets = Insets.Zero,
    strokeFill: Fill = Fill.Transparent,
    strokeThickness: Dp = 0.dp,
    shadows: List<Shadow> = emptyList(),
)

rememberTextComponent(
    style: TextStyle = TextStyle(fontSize = Defaults.TEXT_COMPONENT_TEXT_SIZE.sp),  // color/size/align live here now
    lineCount: Int = Defaults.TEXT_COMPONENT_LINE_COUNT,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    margins: Insets = Insets.Zero,
    padding: Insets = Insets.Zero,
    background: Component? = null,
    minWidth: TextComponent.MinWidth = TextComponent.MinWidth.fixed(),
)
```

- `Fill(color: Color)` / `Fill(brush: Brush)` — construct a Vico `Fill` (the old `fill(Color)`
  free function is gone).
- `Insets(all)`, `Insets(horizontal, vertical)`, `Insets(start, top, end, bottom)` — construct
  insets (the old `insets(…)` free function is gone).
- `LayeredComponent(back, front, padding)` stacks components (used for layered marker indicators).

---

## Scroll & Zoom

Source: `vendor/vico/vico/compose/src/…/cartesian/VicoScrollState.kt` + `VicoZoomState.kt`

```kotlin
// Disable scroll (static chart)
rememberVicoScrollState(scrollEnabled = false)

// Start scrolled to end (e.g. a time series showing the latest data)
rememberVicoScrollState(
    initialScroll = Scroll.Absolute.End,
    autoScroll = Scroll.Absolute.End,
    autoScrollCondition = AutoScrollCondition.OnModelGrowth,   // was OnModelSizeIncreased in 2.x
)
```

`AutoScrollCondition` companion: `Never`, `OnModelGrowth`.

---

## Legend

Source: `vendor/vico/vico/compose/src/…/common/Legends.kt`

```kotlin
val legend = rememberHorizontalLegend<CartesianMeasuringContext, CartesianDrawingContext>(
    items = { extraStore ->
        add(LegendItem(
            icon = rememberShapeComponent(Fill(MaterialTheme.colorScheme.primary), CircleShape),
            labelComponent = rememberTextComponent(),
            label = "Series A",
        ))
    },
    iconSize = 8.dp,
    iconLabelSpacing = 4.dp,
)
rememberCartesianChart(…, legend = legend)
```

`LegendItem(icon, labelComponent, label)`. `rememberVerticalLegend` has the same shape.

---

## Combo chart (line + column)

```kotlin
// Transaction: add the column layer first, then the line
modelProducer.runTransaction {
    columnModel { series(4, 15, 5, 8, 10) }
    lineModel   { series(1,  5, 4, 7,  3) }
}

// Chart: layers in the same order
rememberCartesianChart(
    rememberColumnCartesianLayer(),
    rememberLineCartesianLayer(),
    startAxis = VerticalAxis.rememberStart(),
    bottomAxis = HorizontalAxis.rememberBottom(),
)
```

---

## ExtraStore (passing metadata into a formatter/marker)

`ExtraStore` / `MutableExtraStore` live at `…compose.common.data`. `ExtraStore.Empty` is
internal in 3.x — construct `MutableExtraStore()` when you need an empty one (e.g. in tests).

```kotlin
val labelKey = ExtraStore.Key<List<String>>()

// In the transaction:
modelProducer.runTransaction {
    lineModel { series(data.map { it.x }, data.map { it.y }) }
    extras { it[labelKey] = data.map { d -> d.label } }
}

// In a formatter:
val formatter = CartesianValueFormatter { context, value, _ ->
    context.model.extraStore.getOrNull(labelKey)
        ?.getOrNull(value.toInt()) ?: value.toString()
}
```

---

## Shapes

Vico 3 has **no `CorneredShape`** — use Compose-native shapes everywhere a `Shape` is expected:

```kotlin
androidx.compose.ui.graphics.RectangleShape          // default / rectangle
androidx.compose.foundation.shape.CircleShape        // fully rounded ("pill")
androidx.compose.foundation.shape.RoundedCornerShape(25) // 25% rounded (or a Dp radius)

// Tooltip bubble with a tail (replaces markerCorneredShape(…))
com.patrykandpatrick.vico.compose.common.MarkerCornerBasedShape(base = RoundedCornerShape(4.dp))
```

---

## Migrating from 2.x

The 2.1.3 → 3.2.3 bump (done 2026-08-02) was a package + API break, not a behavioral one.
Every mapping below is exercised in the migrated `ui/` chart files.

| 2.1.3 | 3.2.3 |
|---|---|
| `com.patrykandpatrick.vico.core.*` | `com.patrykandpatrick.vico.compose.*` (the `core` module is gone) |
| `fill(color)` free fn | `Fill(color)` constructor; `Fill(brush)` for gradients |
| `insets(h, v)` free fn | `Insets(h, v)` constructor |
| `LineCartesianLayer.point(comp, size)` | `LineCartesianLayer.Point(comp, size)` constructor |
| `LineStroke.continuous(...)` / `.dashed(...)` | `LineStroke.Continuous(...)` / `.Dashed(...)` |
| `PointConnector.cubic()` / `.Sharp` | `Interpolator.cubic()` / `.catmullRom()` / `.Sharp` (as the `interpolator` arg) |
| `CorneredShape.Pill` / `.rounded(pct)` / `Shape.Rectangle` | `CircleShape` / `RoundedCornerShape(pct)` / `RectangleShape` (Compose) |
| `markerCorneredShape(CorneredShape.Corner.Rounded)` | `MarkerCornerBasedShape(RoundedCornerShape(...))` |
| `rememberTextComponent(color = , textSize = , typeface = )` | `rememberTextComponent(style = TextStyle(color = , fontSize = , …))` |
| `rememberLineComponent/ShapeComponent(shadow = Shadow?)` | `… (shadows = List<Shadow>)` |
| `lineSeries { }` / `columnSeries { }` | `lineModel { }` / `columnModel { }` (old names deprecated, same inner DSL) |
| `MergeMode.grouped(sp)` / `.stacked()` | `ColumnCartesianLayer.MergeMode.Grouped(sp)` / `.Stacked` |
| axis `title: CharSequence?` | axis `title: (ExtraStore) -> CharSequence?` |
| `BaseAxis.Size.auto()` | `BaseAxis.Size.Auto()` |
| `CartesianValueFormatter.Default` | `CartesianValueFormatter.decimal()` |
| `DefaultCartesianMarker.ValueFormatter.Default` | `DefaultCartesianMarker.ValueFormatter.default()` |
| `LineCartesianLayerMarkerTarget.Point.color: Int` (ARGB) | `…Point.color: Color` (Compose) |
| `AutoScrollCondition.OnModelSizeIncreased` | `AutoScrollCondition.OnModelGrowth` |
| `CartesianChartHost(…, consumeMoveEvents = …)` | param removed |
| `ExtraStore.Empty` (public) | `MutableExtraStore()` (Empty is internal now) |
| axis factories imported as free fns (`rememberStart`) | companion members: `VerticalAxis.rememberStart(…)` |
