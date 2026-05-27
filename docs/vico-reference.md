# Vico 2.1.3 Reference (compose-m3)

Source: `vendor/vico/` (gitignored). Always grep/read there first before fetching web docs.

---

## Package map

| What you need | Import from |
|---|---|
| `CartesianChartHost`, `rememberCartesianChart` | `com.patrykandpatrick.vico.compose.cartesian` |
| `rememberLineCartesianLayer`, `rememberColumnCartesianLayer` | `com.patrykandpatrick.vico.compose.cartesian.layer` |
| `rememberLine`, `point` (companion extensions) | `com.patrykandpatrick.vico.compose.cartesian.layer` (import by name) |
| `HorizontalAxis.rememberBottom/Top` | `com.patrykandpatrick.vico.compose.cartesian.axis` |
| `VerticalAxis.rememberStart/End` | `com.patrykandpatrick.vico.compose.cartesian.axis` |
| `rememberDefaultCartesianMarker` | `com.patrykandpatrick.vico.compose.cartesian.marker` |
| `rememberTextComponent`, `rememberLineComponent`, `rememberShapeComponent` | `com.patrykandpatrick.vico.compose.common.component` |
| `fill(Color)` | `com.patrykandpatrick.vico.compose.common` |
| `vicoTheme`, `ProvideVicoTheme` | `com.patrykandpatrick.vico.compose.common` |
| `rememberM3VicoTheme` | `com.patrykandpatrick.vico.compose.m3.common` |
| `rememberHorizontalLegend`, `rememberVerticalLegend` | `com.patrykandpatrick.vico.compose.common` |
| `CartesianChartModelProducer`, `lineSeries`, `columnSeries` | `com.patrykandpatrick.vico.core.cartesian.data` |
| `CartesianValueFormatter` | `com.patrykandpatrick.vico.core.cartesian.data` |
| `CartesianLayerRangeProvider` | `com.patrykandpatrick.vico.core.cartesian.data` |
| `Shape`, `CorneredShape` | `com.patrykandpatrick.vico.core.common.shape` |
| `Insets` | `com.patrykandpatrick.vico.core.common` |
| `insets(h, v)` helper | `com.patrykandpatrick.vico.compose.common` |

---

## Minimal line chart (current project pattern)

```kotlin
// source: app/…/ExerciseDetailScreen.kt
val modelProducer = remember { CartesianChartModelProducer() }

LaunchedEffect(points) {
    modelProducer.runTransaction {
        lineSeries {
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

Source: `vendor/vico/vico/compose/src/…/CartesianChartHost.kt`

```kotlin
CartesianChartHost(
    chart: CartesianChart,
    modelProducer: CartesianChartModelProducer,
    modifier: Modifier = Modifier,
    scrollState: VicoScrollState = rememberVicoScrollState(),
    zoomState: VicoZoomState = rememberDefaultVicoZoomState(scrollState.scrollEnabled),
    animationSpec: AnimationSpec<Float>? = defaultCartesianDiffAnimationSpec,
    animateIn: Boolean = true,
    consumeMoveEvents: Boolean = false,
    placeholder: @Composable BoxScope.() -> Unit = {},
)
```

Default height is `CHART_HEIGHT.dp` (256 dp). Override with `modifier = Modifier.height(200.dp)`.

Static data overload: replace `modelProducer` with `model: CartesianChartModel`.

---

## rememberCartesianChart

Source: `vendor/vico/vico/compose/src/…/CartesianChart.kt`

```kotlin
rememberCartesianChart(
    vararg layers: CartesianLayer<*>,        // rememberLineCartesianLayer(), rememberColumnCartesianLayer()
    startAxis: Axis<…>? = null,             // VerticalAxis.rememberStart(…)
    topAxis: Axis<…>? = null,
    endAxis: Axis<…>? = null,
    bottomAxis: Axis<…>? = null,            // HorizontalAxis.rememberBottom(…)
    marker: CartesianMarker? = null,
    markerVisibilityListener: … = null,
    layerPadding: (ExtraStore) -> CartesianLayerPadding = { cartesianLayerPadding() },
    legend: Legend<…>? = null,
    fadingEdges: FadingEdges? = null,
    decorations: List<Decoration> = emptyList(),
    persistentMarkers: (CartesianChart.PersistentMarkerScope.(ExtraStore) -> Unit)? = null,
    getXStep: (CartesianChartModel) -> Double = { it.getXDeltaGcd() },
)
```

---

## Line layer

Source: `vendor/vico/vico/compose/src/…/layer/LineCartesianLayer.kt`

```kotlin
rememberLineCartesianLayer(
    lineProvider: LineCartesianLayer.LineProvider =
        LineCartesianLayer.LineProvider.series(
            vicoTheme.lineCartesianLayerColors.map { color ->
                LineCartesianLayer.rememberLine(LineCartesianLayer.LineFill.single(fill(color)))
            }
        ),
    pointSpacing: Dp = Defaults.POINT_SPACING.dp,
    rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.auto(),
    verticalAxisPosition: Axis.Position.Vertical? = null,
)
```

### Custom line styling

```kotlin
// Custom color + thickness
val myLine = LineCartesianLayer.rememberLine(
    fill = LineCartesianLayer.LineFill.single(fill(MaterialTheme.colorScheme.primary)),
    stroke = LineCartesianLayer.LineStroke.continuous(thickness = 2.dp),
    areaFill = LineCartesianLayer.AreaFill.single(   // shaded area under the line
        fill(ShaderProvider.verticalGradient(arrayOf(color.copy(alpha = 0.4f), Color.Transparent)))
    ),
    pointConnector = LineCartesianLayer.PointConnector.cubic(),  // smooth curve
)
rememberLineCartesianLayer(
    lineProvider = LineCartesianLayer.LineProvider.series(myLine)
)
```

`LineStroke.dashed(thickness, dashLength, gapLength)` for dashed lines.

`PointConnector`:
- `LineCartesianLayer.PointConnector.Sharp` — straight segments (default)
- `LineCartesianLayer.PointConnector.cubic(curvature)` — smooth Bezier

### Multiple series (different colors)

```kotlin
// Each series() call in the transaction → one line; matched by index to lineProvider
LineCartesianLayer.LineProvider.series(line1, line2, line3)  // vararg
```

---

## Column layer

Source: `vendor/vico/vico/compose/src/…/layer/ColumnCartesianLayer.kt`

```kotlin
rememberColumnCartesianLayer(
    columnProvider: ColumnCartesianLayer.ColumnProvider =
        ColumnCartesianLayer.ColumnProvider.series(
            vicoTheme.columnCartesianLayerColors.map { color ->
                rememberLineComponent(fill(color), Defaults.COLUMN_WIDTH.dp)
            }
        ),
    columnCollectionSpacing: Dp = Defaults.COLUMN_COLLECTION_SPACING.dp,
    mergeMode: (ExtraStore) -> MergeMode = { MergeMode.grouped() },
    dataLabel: TextComponent? = null,
    rangeProvider: CartesianLayerRangeProvider = CartesianLayerRangeProvider.auto(),
    verticalAxisPosition: Axis.Position.Vertical? = null,
)
```

`MergeMode.grouped(columnSpacing)` — side by side.
`MergeMode.stacked()` — stacked bars.

---

## Data: filling the model

Source: `vendor/vico/vico/core/src/…/data/LineCartesianLayerModel.kt` + `ColumnCartesianLayerModel.kt`

```kotlin
// suspend — must be called from a coroutine
modelProducer.runTransaction {
    lineSeries {
        series(x = listOf(1, 2, 3), y = listOf(10.0, 20.0, 15.0))
        series(y = listOf(5, 8, 12))   // indices used as x
        series(5, 8, 12)               // vararg shorthand
    }
    // columnSeries { ... } same API
    // combo chart: add both lineSeries and columnSeries in same transaction
    extras { store -> store[myKey] = someValue }  // attach arbitrary data
}
```

---

## Axes

Source: `vendor/vico/vico/compose/src/…/axis/`

### Bottom / Top (HorizontalAxis)

```kotlin
HorizontalAxis.rememberBottom(
    line: LineComponent? = rememberAxisLineComponent(),
    label: TextComponent? = rememberAxisLabelComponent(),
    labelRotationDegrees: Float = 0f,
    valueFormatter: CartesianValueFormatter = CartesianValueFormatter.Default,
    tick: LineComponent? = rememberAxisTickComponent(),
    tickLength: Dp = Defaults.AXIS_TICK_LENGTH.dp,
    guideline: LineComponent? = rememberAxisGuidelineComponent(),
    itemPlacer: HorizontalAxis.ItemPlacer = HorizontalAxis.ItemPlacer.aligned(),
    size: BaseAxis.Size = BaseAxis.Size.auto(),
    titleComponent: TextComponent? = null,
    title: CharSequence? = null,
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
    verticalLabelPosition: Position.Vertical = Center,
    valueFormatter,
    tick, tickLength, guideline,
    itemPlacer: VerticalAxis.ItemPlacer = VerticalAxis.ItemPlacer.step(),
    size, titleComponent, title,
)
// rememberEnd(…) — same params
```

`itemPlacer` options:
- `VerticalAxis.ItemPlacer.step { stepValue }` — every N units
- `VerticalAxis.ItemPlacer.count { count }` — target number of labels

---

## Value formatters

Source: `vendor/vico/vico/core/src/…/data/CartesianValueFormatter.kt`

```kotlin
// Built-ins
CartesianValueFormatter.decimal(DecimalFormat("#,##0.##"))
CartesianValueFormatter.yPercent()

// Custom (lambda-style)
val formatter = CartesianValueFormatter { context, value, _ ->
    SimpleDateFormat("MMM d", Locale.US).format(Date(value.toLong() * 86_400_000))
}

// Custom range (snap y-axis to clean multiples)
val rangeProvider = object : CartesianLayerRangeProvider {
    override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) =
        floor(minY / 5.0) * 5.0
    override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) =
        ceil(maxY / 5.0) * 5.0
}
```

---

## Marker

Source: `vendor/vico/vico/compose/src/…/marker/DefaultCartesianMarker.kt`
Sample: `vendor/vico/sample/compose/src/…/Marker.kt`

```kotlin
rememberDefaultCartesianMarker(
    label: TextComponent,                    // required
    valueFormatter: DefaultCartesianMarker.ValueFormatter = DefaultCartesianMarker.ValueFormatter.default(),
    labelPosition: DefaultCartesianMarker.LabelPosition = LabelPosition.Top,
    indicator: ((Color) -> Component)? = null,
    indicatorSize: Dp = Defaults.MARKER_INDICATOR_SIZE.dp,
    guideline: LineComponent? = null,
)
```

Minimal styled marker (M3):

```kotlin
@Composable
fun rememberMarker(): CartesianMarker {
    val label = rememberTextComponent(
        color = MaterialTheme.colorScheme.onSurface,
        padding = insets(8.dp, 4.dp),
        background = rememberShapeComponent(
            fill = fill(MaterialTheme.colorScheme.surface),
            shape = markerCorneredShape(CorneredShape.Corner.Rounded),
            strokeThickness = 1.dp,
            strokeFill = fill(MaterialTheme.colorScheme.outline),
        ),
    )
    return rememberDefaultCartesianMarker(
        label = label,
        guideline = rememberAxisGuidelineComponent(),
    )
}
```

Attach to chart: `rememberCartesianChart(…, marker = rememberMarker())`.

---

## M3 Theme

Source: `vendor/vico/vico/compose-m3/src/…/VicoTheme.kt`

```kotlin
// Use M3 color scheme for line/column colors
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

`vicoTheme` (composable property) reads the current theme. Use `vicoTheme.lineCartesianLayerColors[0]` etc. to access active colors.

---

## Components

Source: `vendor/vico/vico/compose/src/…/component/Components.kt`

```kotlin
rememberLineComponent(
    fill: Fill = Fill.Black,
    thickness: Dp = Defaults.LINE_COMPONENT_THICKNESS_DP.dp,
    shape: Shape = Shape.Rectangle,
    margins: Insets = Insets.Zero,
    strokeFill: Fill = Fill.Transparent,
    strokeThickness: Dp = 0.dp,
    shadow: Shadow? = null,
)

rememberShapeComponent(fill, shape, margins, strokeFill, strokeThickness, shadow)

rememberTextComponent(
    color: Color = Color.Black,
    typeface: Typeface = Typeface.DEFAULT,
    textSize: TextUnit = Defaults.TEXT_COMPONENT_TEXT_SIZE.sp,
    textAlignment: Layout.Alignment = ALIGN_NORMAL,
    lineCount: Int = Defaults.TEXT_COMPONENT_LINE_COUNT,
    margins: Insets = Insets.Zero,
    padding: Insets = Insets.Zero,
    background: Component? = null,
    minWidth: TextComponent.MinWidth = TextComponent.MinWidth.fixed(),
)
```

`fill(color: Color): Fill` — converts Compose `Color` to Vico `Fill`.

---

## Scroll & Zoom

Source: `vendor/vico/vico/compose/src/…/VicoScrollState.kt` + `VicoZoomState.kt`

```kotlin
// Disable scroll (static chart)
rememberVicoScrollState(scrollEnabled = false)

// Start scrolled to end (e.g. time series showing latest data)
rememberVicoScrollState(
    initialScroll = Scroll.Absolute.End,
    autoScroll = Scroll.Absolute.End,
    autoScrollCondition = AutoScrollCondition.OnModelSizeIncreased,
)
```

---

## Legend

Source: `vendor/vico/vico/compose/src/…/Legends.kt`

```kotlin
val legend = rememberHorizontalLegend<CartesianMeasuringContext, CartesianDrawingContext>(
    items = { extraStore ->
        add(LegendItem(
            icon = rememberShapeComponent(fill(MaterialTheme.colorScheme.primary), CorneredShape.Pill),
            labelComponent = rememberTextComponent(MaterialTheme.colorScheme.onBackground),
            label = "Series A",
        ))
    },
    iconSize = 8.dp,
    iconLabelSpacing = 4.dp,
)
rememberCartesianChart(…, legend = legend)
```

---

## Combo chart (line + column)

```kotlin
// Transaction: add column layer first, then line
modelProducer.runTransaction {
    columnSeries { series(4, 15, 5, 8, 10) }
    lineSeries  { series(1,  5, 4, 7,  3) }
}

// Chart: layers in same order
rememberCartesianChart(
    rememberColumnCartesianLayer(),
    rememberLineCartesianLayer(),
    startAxis = VerticalAxis.rememberStart(),
    bottomAxis = HorizontalAxis.rememberBottom(),
)
```

---

## ExtraStore (passing metadata into formatter/marker)

```kotlin
val labelKey = ExtraStore.Key<List<String>>()

// In transaction:
modelProducer.runTransaction {
    lineSeries { series(data.map { it.x }, data.map { it.y }) }
    extras { it[labelKey] = data.map { d -> d.label } }
}

// In formatter:
val formatter = CartesianValueFormatter { context, value, _ ->
    context.model.extraStore.getOrNull(labelKey)
        ?.getOrNull(value.toInt()) ?: value.toString()
}
```

---

## Shapes

```kotlin
Shape.Rectangle                          // default
CorneredShape.Pill                       // fully rounded
CorneredShape.rounded(allPercent = 25)   // 25% rounded
markerCorneredShape(CorneredShape.Corner.Rounded)  // tooltip bubble with tail
```

Import `markerCorneredShape` from `com.patrykandpatrick.vico.compose.common.shape`.
