# Group Map Feature - Quick Start Guide

## Prerequisites - المتطلبات الأساسية

1. **VTPK Files**: Place your VTPK files in `vector/src/main/assets/`
2. **ArcGIS SDK**: Already added to build.gradle
3. **Android Studio**: Latest version recommended

## Quick Setup - الإعداد السريع

### Step 1: Add VTPK Files
```bash
# Copy your VTPK files to assets folder
cp your_map.vtpk vector/src/main/assets/
```

### Step 2: Update VtpkMapProvider
```kotlin
// In VtpkMapProvider.kt, add your filename
companion object {
    private const val YOUR_MAP_FILENAME = "your_map.vtpk"
    // ... existing constants
}

fun getYourMapPath(): String? {
    return getVtpkFilePath(YOUR_MAP_FILENAME)
}
```

### Step 3: Add Strings
```xml
<!-- In strings.xml -->
<string name="your_map_name">Your Map Name</string>

<!-- In strings-ar.xml -->
<string name="your_map_name">اسم خريطتك</string>
```

### Step 4: Test
1. Build and run the app
2. Open any room
3. Go to Room Settings
4. Tap "Group Map"
5. Verify map loads correctly

## File Structure - هيكل الملفات

```
vector/src/main/java/im/vector/app/features/roomprofile/groupmap/
├── GroupMapActivity.kt          # Main activity
├── GroupMapFragment.kt          # Map display fragment
├── GroupMapViewModel.kt         # Business logic
└── VtpkMapProvider.kt          # VTPK file management

vector/src/main/res/layout/
├── activity_group_map.xml       # Activity layout
└── fragment_group_map.xml       # Fragment layout

vector/src/main/assets/
├── OpenStreetMap_F9A0B63D-2DDB-47F1-B1CC-BCFCB403DC14.vtpk
└── Nova_9233B27F-012A-4847-8687-8BD434431EDF.vtpk
```

## Key Components - المكونات الرئيسية

### VtpkMapProvider
- Manages VTPK file paths
- Handles file copying from assets
- Provides file availability checks

### GroupMapViewModel
- Manages map loading state
- Handles member data loading
- Coordinates between UI and data

### GroupMapFragment
- Displays the map
- Handles user interactions
- Manages map lifecycle

## Common Tasks - المهام الشائعة

### Adding a New Map Type
1. Add VTPK file to assets
2. Update VtpkMapProvider with new filename
3. Add corresponding strings
4. Test map loading

### Customizing Map Display
1. Modify `initializeMap()` in GroupMapFragment
2. Update map symbols and overlays
3. Customize initial viewpoint

### Adding Member Pins
1. Update GroupMapViewModel to load member data
2. Create graphics overlays in GroupMapFragment
3. Add pin symbols and interactions

## Testing - الاختبار

### Offline Testing
1. Disable internet connection
2. Open group map
3. Verify map loads from local files
4. Check error handling for missing files

### Performance Testing
1. Test with large VTPK files
2. Monitor memory usage
3. Check file copying performance
4. Verify map rendering speed

## Troubleshooting - حل المشاكل

### Build Errors
```bash
# Clean and rebuild
./gradlew clean
./gradlew assembleDebug
```

### Runtime Errors
- Check logcat for detailed error messages
- Verify VTPK file integrity
- Ensure sufficient device storage

### Map Not Loading
- Verify VTPK files in assets folder
- Check file names match exactly
- Ensure ArcGIS SDK is properly added

## Next Steps - الخطوات التالية

1. **Phase 1**: Basic map display ✅
2. **Phase 2**: Member display on map
3. **Phase 3**: Advanced features (routing, layers)
4. **Phase 4**: Performance optimization

## Support - الدعم

- Check the main documentation: `docs/group_map_feature.md`
- Review ArcGIS Maps SDK documentation
- Check project issues and discussions
