/*
 * -----------------------------------------------------------------------------
 *  Copyright (C) 2023 University of Dundee. All rights reserved.
 *
 *
 *  Redistribution and use in source and binary forms, with or without modification, 
 *  are permitted provided that the following conditions are met:
 * 
 *  Redistributions of source code must retain the above copyright notice,
 *  this list of conditions and the following disclaimer.
 *  Redistributions in binary form must reproduce the above copyright notice, 
 *  this list of conditions and the following disclaimer in the documentation
 *  and/or other materials provided with the distribution.
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 *  ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 *  OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 *  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, 
 *  INCIDENTAL, SPECIAL, EXEMPLARY OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 *  HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 *  (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 *  OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ------------------------------------------------------------------------------
 */

/*
 * This Groovy script shows how to analyse an OMERO dataset
 * i.e. a collection of OMERO images.
 * For that example, we use the analyse particles plugin.
 * The generated ROIs are then saved back to OMERO.
 * We create a summary CSV and a summary table of the measurement and attach
 * them to the dataset.
 * Use this script in the Scripting Dialog of Fiji (File > New > Script).
 * Select Groovy as language in the Scripting Dialog.
 * Error handling is omitted to ease the reading of the script but this
 * should be added if used in production to make sure the services are closed
 * Information can be found at
 * https://docs.openmicroscopy.org/latest/omero5/developers/Java.html
 */

#@ String(label="Username") USERNAME
#@ String(label="Password", style='password', persist=false) PASSWORD
#@ String(label="Host", value='wss://workshop.openmicroscopy.org/omero-ws') HOST
#@ Integer(label="Dataset ID", value=2920) dataset_id

import java.util.ArrayList
import java.io.File
import java.io.PrintWriter

import java.nio.file.Files

// OMERO Dependencies
import omero.gateway.Gateway
import omero.gateway.LoginCredentials
import omero.gateway.SecurityContext
import omero.gateway.facility.BrowseFacility
import omero.gateway.facility.DataManagerFacility
import omero.gateway.facility.ROIFacility
import omero.log.SimpleLogger

import omero.gateway.model.DatasetData
import omero.model.DatasetI

import org.openmicroscopy.shoola.util.roi.io.ROIReader

import ij.IJ
import ij.plugin.frame.RoiManager
import ij.measure.ResultsTable


def connect_to_omero() {
    "Connect to OMERO"

    credentials = new LoginCredentials()
    credentials.getServer().setHostname(HOST)
    credentials.getUser().setUsername(USERNAME.trim())
    credentials.getUser().setPassword(PASSWORD.trim())
    simpleLogger = new SimpleLogger()
    gateway = new Gateway(simpleLogger)
    gateway.connect(credentials)
    return gateway
}

def get_images(gateway, ctx, dataset_id) {
    "List all images contained in a Dataset"

    browse = gateway.getFacility(BrowseFacility)
    ids = new ArrayList(1)
    ids.add(new Long(dataset_id))
    return browse.getImagesForDatasets(ctx, ids)
}


def get_port(HOST) {
    port = 4064
    // check if websockets is used
    if (HOST.startsWith("ws")) {
        port = 443
    }
    return port
}

def open_image_plus(HOST, USERNAME, PASSWORD, group_id, image_id) {
    "Open the image using the Bio-Formats Importer"

    StringBuilder options = new StringBuilder()
    options.append("location=[OMERO] open=[omero:server=")
    options.append(HOST)
    options.append("\nuser=")
    options.append(USERNAME.trim())
    options.append("\nport=")
    options.append(get_port(HOST))
    options.append("\npass=")
    options.append(PASSWORD.trim())
    options.append("\ngroupID=")
    options.append(group_id)
    options.append("\niid=")
    options.append(image_id)
    options.append("] ")
    options.append("windowless=true view=Hyperstack ")
    IJ.runPlugIn("loci.plugins.LociImporter", options.toString())
}


def save_rois_to_omero(ctx, image_id, imp) {
    " Save ROI's back to OMERO"
    reader = new ROIReader()
    roi_list = reader.readImageJROIFromSources(image_id, imp)
    roi_facility = gateway.getFacility(ROIFacility)
    result = roi_facility.saveROIs(ctx, image_id, exp_id, roi_list)
}


def save_row(rt, table_rows, image) {
    "Create a summary table of the measurements"
    // Remove the rows not corresponding to the specified channel
    to_delete = new ArrayList()
    
    // We only keep the first channel. Index starts at 1 in ImageJ
    ref = "c:" + 1
    max_bounding_box = 0.0f
    for (i = 0; i < rt.size(); i++) {
        label = rt.getStringValue("Label", i)
        if (label != null && label.contains(ref)) {
            w = rt.getStringValue("Width", i)
            h = rt.getStringValue("Height", i)
            area = Float.parseFloat(w) * Float.parseFloat(h)
            max_bounding_box = Math.max(area, max_bounding_box)
        }
    }
    // Rename the table so we can read the summary table
    IJ.renameResults("Results")
    rt = ResultsTable.getResultsTable()
    for (i = 0; i < rt.size(); i++) {
        value = rt.getStringValue("Slice", i)
        if (!value.startsWith(ref)) {
            to_delete.add(i)
        }
    }
    // Delete the rows we do not need
    for (i = 0; i < rt.size(); i++) {
        value = to_delete.get(i)
        v = value-i
        rt.deleteRow(v)
    }
    rt.updateResults()
    // Insert values in summary table
    for (i = 0; i < rt.size(); i++) {
        rt.setValue("Bounding_Box", i, max_bounding_box)
    }
    headings = rt.getHeadings()
    for (i = 0; i < headings.length; i++) {
        row = new ArrayList()
        for (j = 0; j < rt.size(); j++) {
            for (i = 0; i < headings.length; i++) {
                heading = rt.getColumnHeading(i)
                if (heading.equals("Slice") || heading.equals("Dataset")) {
                    row.add(rt.getStringValue(i, j))
                } else {
                    row.add(new Double(rt.getValue(i, j)))
                }
            }
        }
        row.add(image.getId())
        table_rows.add(row)
    }
    return headings
}

def create_table_columns(headings) {
    "Create the table headings from the ImageJ results table"
    size = headings.size()
    table_columns = new String[size+1]
    //populate the headings
    for (h = 0; h < headings.size(); h++) {
        heading = headings[h]
        // OMERO.tables queries don't handle whitespace well
        heading = heading.replace(" ", "_")
        table_columns[h] = heading
    }
    table_columns[size] = "Image"
    return table_columns
}


def save_summary_as_csv(file, rows, columns) {
    "Save the summary locally as a CSV"
    stream = null
    sb = new StringBuilder()
    try {
        stream = new PrintWriter(file)
        l = columns.length
        for (i = 0; i < l; i++) {
            sb.append(columns[i])
            if (i != (l-1)) {
                sb.append(", ")
            }
        }
        sb.append("\n")
        rows.each() { row ->
            size = row.size()
            for (i = 0; i < size; i++) {
                value = row.get(i)
                sb.append(value)
                if (i != (size-1)) {
                    sb.append(", ")
                }
            }
            sb.append("\n")
        }
        stream.write(sb.toString())
    } finally {
        stream.close()
    }
}

def upload_csv_to_omero(ctx, file, dataset_id) {
    "Upload the CSV file and attach it to the specified dataset"
    svc = gateway.getFacility(DataManagerFacility)
    data = new DatasetData(new DatasetI(dataset_id, false))
    namespace = "training.demo"
    mimetype = "text/csv"
    future = svc.attachFile(ctx, file, mimetype, "", namespace, data)
    future.get()
}

// Prototype analysis example
gateway = connect_to_omero()
exp = gateway.getLoggedInUser()
group_id = exp.getGroupId()
ctx = new SecurityContext(group_id)
exp_id = exp.getId()


// get all images in an omero dataset
images = get_images(gateway, ctx, dataset_id)

table_rows = new ArrayList()
table_columns = null

count = 0
//Close all windows before starting
IJ.run("Close All")
if (images.size() == 0) {
    gateway.disconnect() //close the connection
    println "no images to analyze"
    return
}
RoiManager.getRoiManager()
images.each() { image ->
    // Open the image
    id = image.getId()
    open_image_plus(HOST, USERNAME, PASSWORD, group_id, String.valueOf(id))
    imp = IJ.getImage()
    // Analyse the images. This section could be replaced by any other macro
    IJ.run("8-bit")
    //white might be required depending on the version of Fiji
    IJ.run(imp, "Auto Threshold", "method=MaxEntropy stack")
    IJ.run(imp, "Analyze Particles...", "size=10-Infinity pixel display clear add stack summarize")
    IJ.run("Set Measurements...", "area mean standard modal min centroid center perimeter bounding feret's summarize stack display redirect=None decimal=3")
    // If you wish to run a macro file saved locally
    // Comment the lines IJ.run above and replace by
    // IJ.runMacroFile("/path/to/Macrofile")

    rm = RoiManager.getInstance()
    rm.runCommand(imp, "Measure")
    rt = ResultsTable.getResultsTable()
    // Save the ROIs back to OMERO
    save_rois_to_omero(ctx, id, imp)
    println "creating summary results for image ID " + id
    headings = save_row(rt, table_rows, image)
    if (table_columns == null) {
        table_columns = create_table_columns(headings)
    }
    
    // Close the various components
    IJ.selectWindow("Results")
    IJ.run("Close")
    IJ.selectWindow("ROI Manager")
    IJ.run("Close")
    imp.changes = false     // Prevent "Save Changes?" dialog
    imp.close()
    
}

// Create the result file
tmp_dir = Files.createTempDirectory("Fiji_csv")
path = tmp_dir.resolve("idr0021_merged_results.csv")
file_path = Files.createFile(path)
file = new File(file_path.toString())

// Create a CSV file and upload it
save_summary_as_csv(file, table_rows, table_columns)
upload_csv_to_omero(ctx, file, dataset_id)

// Delete the local copy of the temporary file and directory
dir = new File(tmp_dir.toString())
entries = dir.listFiles()
for (i = 0; i < entries.length; i++) {
    entries[i].delete()
}
dir.delete()

// Close the connection
gateway.disconnect()
println "processing done"
