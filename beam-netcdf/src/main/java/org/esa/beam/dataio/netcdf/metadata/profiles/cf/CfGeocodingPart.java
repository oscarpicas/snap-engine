/*
 * $Id$
 *
 * Copyright (C) 2010 by Brockmann Consult (info@brockmann-consult.de)
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.dataio.netcdf.metadata.profiles.cf;

import org.esa.beam.dataio.netcdf.metadata.ProfilePart;
import org.esa.beam.dataio.netcdf.metadata.ProfileReadContext;
import org.esa.beam.dataio.netcdf.metadata.ProfileWriteContext;
import org.esa.beam.dataio.netcdf.util.AttributeMap;
import org.esa.beam.dataio.netcdf.util.Constants;
import org.esa.beam.dataio.netcdf.util.Dimension;
import org.esa.beam.dataio.netcdf.util.ReaderUtils;
import org.esa.beam.framework.dataio.ProductIOException;
import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.CrsGeoCoding;
import org.esa.beam.framework.datamodel.GeoCoding;
import org.esa.beam.framework.datamodel.GeoPos;
import org.esa.beam.framework.datamodel.PixelGeoCoding;
import org.esa.beam.framework.datamodel.PixelPos;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.util.logging.BeamLogManager;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import ucar.ma2.Array;
import ucar.ma2.DataType;
import ucar.ma2.Index;
import ucar.ma2.InvalidRangeException;
import ucar.nc2.Attribute;
import ucar.nc2.NetcdfFile;
import ucar.nc2.NetcdfFileWriteable;
import ucar.nc2.Variable;

import java.io.IOException;

public class CfGeocodingPart extends ProfilePart {

    private boolean mustWriteLatLonDatasets;

    @Override
    public void read(ProfileReadContext ctx, Product p) throws IOException {
        readGeocoding(ctx, p);
    }

    @Override
    public void define(ProfileWriteContext ctx, Product product) throws
                                                                 IOException {
        final GeoCoding geoCoding = product.getGeoCoding();
        mustWriteLatLonDatasets = !isGeographicLatLon(geoCoding);
        final NetcdfFileWriteable ncFile = ctx.getNetcdfFileWriteable();
        if (mustWriteLatLonDatasets) {
            addXYCoordVariables(ncFile);
            addLatLonBands(ncFile);
        } else {
            GeoPos ul = geoCoding.getGeoPos(new PixelPos(0.5f, 0.5f), null);
            GeoPos br = geoCoding.getGeoPos(
                    new PixelPos(product.getSceneRasterWidth() - 0.5f, product.getSceneRasterHeight() - 0.5f), null);
            addLatLonCoordVariables(ncFile, ul, br);
        }
        ctx.setProperty(Constants.Y_FLIPPED_PROPERTY_NAME, true);
    }

    @Override
    public void write(ProfileWriteContext ctx, Product product) throws IOException {
        if (!mustWriteLatLonDatasets) {
            return;
        }
        try {
            final int h = product.getSceneRasterHeight();
            final int w = product.getSceneRasterWidth();
            final float[] lat = new float[w];
            final float[] lon = new float[w];
            PixelPos pixelPos = new PixelPos();
            GeoPos geoPos = new GeoPos();
            final Boolean isYFlipped = (Boolean) ctx.getProperty(Constants.Y_FLIPPED_PROPERTY_NAME);
            for (int y = 0; y < h; y++) {
                pixelPos.y = y + 0.5f;
                for (int x = 0; x < w; x++) {
                    pixelPos.x = x + 0.5f;
                    product.getGeoCoding().getGeoPos(pixelPos, geoPos);
                    lat[x] = geoPos.getLat();
                    lon[x] = geoPos.getLon();
                }
                int flippedY = isYFlipped ? (h - 1) - y : y;
                final int[] shape = new int[]{1, w};
                final int[] origin = new int[]{flippedY, 0};
                ctx.getNetcdfFileWriteable().write("lat", origin, Array.factory(DataType.FLOAT, shape, lat));
                ctx.getNetcdfFileWriteable().write("lon", origin, Array.factory(DataType.FLOAT, shape, lon));
            }
        } catch (InvalidRangeException e) {
            final ProductIOException productIOException = new ProductIOException(
                    "Data not in the expected range");
            productIOException.initCause(e);
            throw productIOException;
        }
    }

    static boolean isGeographicLatLon(final GeoCoding geoCoding) {
        return geoCoding instanceof CrsGeoCoding
               && CRS.equalsIgnoreMetadata(geoCoding.getMapCRS(), DefaultGeographicCRS.WGS84);
    }

    private void addLatLonCoordVariables(NetcdfFileWriteable ncFile, GeoPos ul, GeoPos br) {
        final Variable latVar = ncFile.addVariable(null, "lat", DataType.FLOAT, "lat");
        latVar.addAttribute(new Attribute("units", "degrees_north"));
        latVar.addAttribute(new Attribute("long_name", "latitude coordinate"));
        latVar.addAttribute(new Attribute("standard_name", "latitude"));
        latVar.addAttribute(new Attribute(Constants.VALID_MIN_ATT_NAME, br.getLat()));
        latVar.addAttribute(new Attribute(Constants.VALID_MAX_ATT_NAME, ul.getLat()));

        final Variable lonVar = ncFile.addVariable(null, "lon", DataType.FLOAT, "lon");
        lonVar.addAttribute(new Attribute("units", "degrees_east"));
        lonVar.addAttribute(new Attribute("long_name", "longitude coordinate"));
        lonVar.addAttribute(new Attribute("standard_name", "longitude"));
        lonVar.addAttribute(new Attribute(Constants.VALID_MIN_ATT_NAME, ul.getLon()));
        lonVar.addAttribute(new Attribute(Constants.VALID_MAX_ATT_NAME, br.getLon()));
    }

    private void addXYCoordVariables(NetcdfFileWriteable ncFile) {
        final Variable yVar = ncFile.addVariable(null, "y", DataType.FLOAT, "y");
        yVar.addAttribute(new Attribute("axis", "y"));
        yVar.addAttribute(new Attribute("long_name", "y-coordinate in Cartesian system"));
        yVar.addAttribute(new Attribute("units", "m"));             // todo check

        final Variable xVar = ncFile.addVariable(null, "x", DataType.FLOAT, "x");
        xVar.addAttribute(new Attribute("axis", "x"));
        xVar.addAttribute(new Attribute("long_name", "x-coordinate in Cartesian system"));
        xVar.addAttribute(new Attribute("units", "m"));             // todo check
    }

    private void addLatLonBands(final NetcdfFileWriteable ncFile) {
        final Variable latVar = ncFile.addVariable(null, "lat", DataType.FLOAT, "y x");
        latVar.addAttribute(new Attribute("units", "degrees_north"));
        latVar.addAttribute(new Attribute("long_name", "latitude coordinate"));
        latVar.addAttribute(new Attribute("standard_name", "latitude"));

        final Variable lonVar = ncFile.addVariable(null, "lon", DataType.FLOAT, "y x");
        lonVar.addAttribute(new Attribute("units", "degrees_east"));
        lonVar.addAttribute(new Attribute("long_name", "longitude coordinate"));
        lonVar.addAttribute(new Attribute("standard_name", "longitude"));
    }

    public static void readGeocoding(ProfileReadContext ctx, Product product) throws IOException {
        GeoCoding geoCoding = createConventionBasedMapGeoCoding(ctx, product);
        if (geoCoding == null) {
            geoCoding = createPixelGeoCoding(ctx, product);
        }
        if (geoCoding != null) {
            product.setGeoCoding(geoCoding);
        }
    }

    public static GeoCoding createConventionBasedMapGeoCoding(ProfileReadContext ctx, Product product) {
        final String[] cfConvention_lonLatNames = new String[]{
                Constants.LON_VAR_NAME,
                Constants.LAT_VAR_NAME
        };
        final String[] coardsConvention_lonLatNames = new String[]{
                Constants.LONGITUDE_VAR_NAME,
                Constants.LATITUDE_VAR_NAME
        };

        Variable[] lonLat;
        lonLat = ReaderUtils.getVariables(ctx.getGlobalVariables(), cfConvention_lonLatNames);
        if (lonLat == null) {
            lonLat = ReaderUtils.getVariables(ctx.getGlobalVariables(), coardsConvention_lonLatNames);
        }

        if (lonLat != null) {
            final Variable lonVariable = lonLat[0];
            final Variable latVariable = lonLat[1];
            final Dimension rasterDim = ctx.getRasterDigest().getRasterDim();
            if (rasterDim.fitsTo(lonVariable, latVariable)) {
                try {
                    return createConventionBasedMapGeoCoding(lonVariable, latVariable,
                                                             product.getSceneRasterWidth(),
                                                             product.getSceneRasterHeight(), ctx);
                } catch (Exception e) {
                    BeamLogManager.getSystemLogger().warning("Failed to create NetCDF geo-coding");
                }
            }
        }
        return null;
    }

    private static GeoCoding createConventionBasedMapGeoCoding(Variable lonVar,
                                                               Variable latVar,
                                                               int sceneRasterWidth,
                                                               int sceneRasterHeight,
                                                               ProfileReadContext ctx) throws Exception {
        double pixelX;
        double pixelY;
        double easting;
        double northing;
        double pixelSizeX;
        double pixelSizeY;

        final AttributeMap lonAttrMap = AttributeMap.create(lonVar);
        final Number lonValidMin = lonAttrMap.getNumericValue(Constants.VALID_MIN_ATT_NAME);
        final Number lonValidMax = lonAttrMap.getNumericValue(Constants.VALID_MAX_ATT_NAME);

        final AttributeMap latAttrMap = AttributeMap.create(latVar);
        final Number latValidMin = latAttrMap.getNumericValue(Constants.VALID_MIN_ATT_NAME);
        final Number latValidMax = latAttrMap.getNumericValue(Constants.VALID_MAX_ATT_NAME);

        boolean yFlipped;
        if (lonValidMin != null && lonValidMax != null && latValidMin != null && latValidMax != null) {
            // COARDS convention uses 'valid_min' and 'valid_max' attributes
            pixelX = 0.5;
            pixelY = (sceneRasterHeight - 1.0) + 0.5;
            easting = lonValidMin.doubleValue();
            northing = latValidMin.doubleValue();
            pixelSizeX = (lonValidMax.doubleValue() - lonValidMin.doubleValue()) / sceneRasterWidth;
            pixelSizeY = (latValidMax.doubleValue() - latValidMin.doubleValue()) / sceneRasterHeight;
            // must flip
            yFlipped = true; // todo - check
        } else {
            // CF convention
            final Array lonData = lonVar.read();
            final Array latData = latVar.read();

            final Index i0 = lonData.getIndex().set(0);
            final Index i1 = lonData.getIndex().set(1);
            pixelSizeX = lonData.getDouble(i1) - lonData.getDouble(i0);
            easting = lonData.getDouble(i0);

            int latSize = (int) latVar.getSize();
            final Index j0 = latData.getIndex().set(0);
            final Index j1 = latData.getIndex().set(1);
            pixelSizeY = latData.getDouble(j1) - latData.getDouble(j0);

            pixelX = 0.5f;
            pixelY = 0.5f;

            // this should be the 'normal' case
            if (pixelSizeY < 0) {
                pixelSizeY = -pixelSizeY;
                yFlipped = false;
                northing = latData.getDouble(latData.getIndex().set(0));
            } else {
                yFlipped = true;
                northing = latData.getDouble(latData.getIndex().set(latSize - 1));
            }
        }

        if (pixelSizeX <= 0 || pixelSizeY <= 0) {
            return null;
        }
        ctx.setProperty(Constants.Y_FLIPPED_PROPERTY_NAME, yFlipped);
        return new CrsGeoCoding(DefaultGeographicCRS.WGS84,
                                sceneRasterWidth, sceneRasterHeight,
                                easting, northing,
                                pixelSizeX, pixelSizeY,
                                pixelX, pixelY);
    }

    private static GeoCoding createPixelGeoCoding(ProfileReadContext ctx, Product product) throws IOException {
        Band lonBand = product.getBand(Constants.LON_VAR_NAME);
        if (lonBand == null) {
            lonBand = product.getBand(Constants.LONGITUDE_VAR_NAME);
        }
        Band latBand = product.getBand(Constants.LAT_VAR_NAME);
        if (latBand == null) {
            latBand = product.getBand(Constants.LATITUDE_VAR_NAME);
        }
        if (latBand != null && lonBand != null) {
            final NetcdfFile netcdfFile = ctx.getNetcdfFile();
            ctx.setProperty(Constants.Y_FLIPPED_PROPERTY_NAME,
                            detectFlipping(netcdfFile.findTopVariable(latBand.getName())));
            return new PixelGeoCoding(latBand, lonBand, latBand.getValidPixelExpression(), 5);
        }
        return null;
    }

    private static boolean detectFlipping(Variable latVar) throws IOException {
        final Array latData = latVar.read();
        final Index j0 = latData.getIndex().set(0);
        final Index j1 = latData.getIndex().set(1);
        double pixelSizeY = latData.getDouble(j1) - latData.getDouble(j0);

        // this should be the 'normal' case
        return pixelSizeY >= 0;
    }
}
