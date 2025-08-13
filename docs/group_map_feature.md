# Group Map Feature - خريطة المجموعة

## Overview - نظرة عامة

This feature adds an offline map to group/room settings that displays group members on a map. The map works without internet connection using local VTPK (Vector Tile Package) files.

هذه الميزة تضيف خريطة تعمل بدون إنترنت إلى إعدادات المجموعات/الغرف لعرض أعضاء المجموعة على خريطة.

## Features - الميزات

- **Offline Maps**: Works without internet connection using local VTPK files
- **Group Member Display**: Shows group members on the map (future enhancement)
- **UAE Map Support**: Includes UAE OpenStreetMap data
- **Nova Map Support**: Includes Nova map data
- **Easy Access**: Accessible from room settings

## Implementation Details - تفاصيل التنفيذ

### 1. Files Added - الملفات المضافة

#### Activities & Fragments
- `GroupMapActivity.kt` - Main activity for the map screen
- `GroupMapFragment.kt` - Fragment containing the map view
- `GroupMapViewModel.kt` - ViewModel managing map state and data

#### Providers & Utilities
- `VtpkMapProvider.kt` - Manages VTPK map files

#### Layouts
- `activity_group_map.xml` - Layout for GroupMapActivity
- `fragment_group_map.xml` - Layout for GroupMapFragment

#### Strings
- Added Arabic and English strings for the feature

### 2. Dependencies - التبعيات

Added ArcGIS Maps SDK for Kotlin:
```gradle
implementation 'com.esri.arcgisruntime:arcgis-android:200.8.0'
```

### 3. Navigation - التنقل

The feature is accessible from:
- Room Settings → Group Map option
- Calls `GroupMapActivity.getIntent(context, roomId)`

### 4. VTPK Files - ملفات الخريطة

#### Required Files
Place these VTPK files in `vector/src/main/assets/`:

1. **OpenStreetMap_F9A0B63D-2DDB-47F1-B1CC-BCFCB403DC14.vtpk**
   - Contains UAE OpenStreetMap data
   - Used as default map

2. **Nova_9233B27F-012A-4847-8687-8BD434431EDF.vtpk**
   - Contains Nova map data
   - Available as alternative map

#### File Management
- Files are automatically copied from assets to internal storage
- Stored in `context.filesDir/maps/` directory
- Copied on first use for faster access

## Usage - الاستخدام

### For Users - للمستخدمين

1. Open any group/room
2. Go to Room Settings (⚙️)
3. Tap "Group Map" option
4. View the offline map

### For Developers - للمطورين

#### Adding New Maps
1. Add VTPK file to assets folder
2. Update `VtpkMapProvider.kt` with new filename
3. Add corresponding string resources

#### Customizing Map Display
- Modify `GroupMapFragment.kt` for UI changes
- Update `GroupMapViewModel.kt` for business logic
- Customize map symbols and overlays in fragment

## Future Enhancements - التحسينات المستقبلية

### Phase 2: Member Display
- Show group members as pins on the map
- Display member names and avatars
- Allow member selection and interaction

### Phase 3: Advanced Features
- Multiple map styles
- Member location sharing
- Route planning between members
- Custom map layers

## Technical Notes - ملاحظات تقنية

### ArcGIS Maps SDK
- Uses version 200.8.0
- Supports offline VTPK files
- Provides vector tile rendering
- Includes symbol and graphics support

### Performance Considerations
- VTPK files can be large (100MB+)
- Files are copied to internal storage for faster access
- Map rendering is optimized for mobile devices

### Error Handling
- Graceful fallback if VTPK files are missing
- User-friendly error messages
- Logging for debugging purposes

## Troubleshooting - حل المشاكل

### Common Issues

1. **Map not loading**
   - Check if VTPK files are in assets folder
   - Verify file names match exactly
   - Check device storage space

2. **App crashes on map open**
   - Ensure ArcGIS SDK is properly added
   - Check for missing dependencies
   - Verify VTPK file integrity

3. **Map appears blank**
   - Check if VTPK files are valid
   - Verify file copying to internal storage
   - Check device compatibility

### Debug Information
- Check logcat for detailed error messages
- Verify VTPK file paths in logs
- Check map initialization status

## Contributing - المساهمة

When contributing to this feature:

1. Follow existing code style
2. Add proper error handling
3. Include Arabic translations
4. Test offline functionality
5. Update documentation

## License - الترخيص

This feature follows the same license as the main project (AGPL-3.0-only OR LicenseRef-Element-Commercial).
