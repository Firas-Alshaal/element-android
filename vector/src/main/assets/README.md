# VTPK Map Files

This directory should contain the VTPK (Vector Tile Package) files for offline maps.

## Required Files

1. **OpenStreetMap_F9A0B63D-2DDB-47F1-B1CC-BCFCB403DC14.vtpk** - UAE OpenStreetMap data
2. **Nova_9233B27F-012A-4847-8687-8BD434431EDF.vtpk** - Nova map data

## How to Add VTPK Files

1. Place your VTPK files in this directory
2. Ensure the filenames match exactly (case-sensitive)
3. The app will automatically copy these files to internal storage on first use

## File Format

VTPK files are compressed packages containing vector tile data for offline map rendering.
These files are typically created using ArcGIS Pro or similar GIS software.

## Notes

- VTPK files can be large (several hundred MB)
- The app will copy these files to internal storage for faster access
- Make sure you have sufficient storage space on the device
