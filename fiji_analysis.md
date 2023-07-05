## Analyze particles using Fiji

This notebook demonstrates how to analyze a batch of images associated to the paper ['Subdiffraction imaging of centrosomes reveals higher-order organizational features of pericentriolar material.'](http://dx.doi.org/10.1038/ncb2591) using the scripting facility available in [Fiji](https://fiji.sc/)

Fiji has been installed with few other plugins including the omero_ij plugin to allow to connect to an OMERO server.
See See [installation](https://omero-guides.readthedocs.io/en/latest/fiji/docs/installation.html)

This session is based on content available in the [OMERO Fiji guide]( 
https://omero-guides.readthedocs.io/en/latest/fiji/docs/index.html).


## Learning objectives

* How to connect to OMERO from Fiji using the Java API
 * How to open an OMERO image using Bio-Formats (Java library used to read images)
 * How to analyse an image using Fiji
 * How to save ROIs back to OMERO
 * How to save the results as CSV to OMERO
 
We first look on how the script is set up. 
The connection and how the images are read using Bio-Formats are the same. There are other ways to read the images or part of the images e.g. one plane. This is outside the scope of this session. If interested, we recommend to review the scripts available in the omero-guide-fiji GitHub [repository](https://github.com/ome/omero-guide-fiji/tree/master/scripts/groovy).

Before you start:

* First install the omero imageJ plugin if not already installed. See [installation](https://omero-guides.readthedocs.io/en/latest/fiji/docs/installation.html).
* To do manual analysis, see [manual analysis](https://omero-guides.readthedocs.io/en/latest/fiji/docs/manual_analysis.html).
* Script the analysis, see [scripting analysis](https://omero-guides.readthedocs.io/en/latest/fiji/docs/threshold_scripting.html). The ``groovy`` script used for this workshop is available under the ``scripts`` folder.


## Import packages
At the beginning of a script, you need to import the packages that will be required to connect to OMERO, run the images and interact with ImageJ commands.

```
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
```

## Connection information 
Collection information required to connect to the OMERO server or IDR.

```
#@ String(label="Username") USERNAME
#@ String(label="Password", style='password', persist=false) PASSWORD
#@ String(label="Host", value='wss://workshop.openmicroscopy.org/omero-ws') HOST
#@ Integer(label="Dataset ID", value=2920) dataset_id
```

## Connect to OMERO

This method establishes a connection with the OMERO server so we can analyse the images in Fiji.

// Method to connect to OMERO
def connect_to_omero(host, user, password) {
    "Connect to OMERO"

    credentials = new LoginCredentials()
    credentials.getServer().setHostname(host)
    credentials.getUser().setUsername(user.trim())
    credentials.getUser().setPassword(password.trim())
    simpleLogger = new SimpleLogger()
    gateway = new Gateway(simpleLogger)
    gateway.connect(credentials)
    return gateway
}

// Connect to OMERO
gateway = connect_to_omero(HOST, USERNAME, PASSWORD)

## Retrieve images in a dataset
In the method below, we only retrieve the information about the images e.g. name, identifier, etc. We **do not** load binary data yet

```
def get_images(gateway, ctx, dataset_id) {
    "List all images contained in a Dataset"

    browse = gateway.getFacility(BrowseFacility)
    ids = new ArrayList(1)
    ids.add(new Long(dataset_id))
    return browse.getImagesForDatasets(ctx, ids)
}
```

## Open the image using Bio-Formats

We use the Bio-Formats plugin to read the images i.e. we read the binary data from the OMERO.server

We pass the ``image_id`` to the plugin.
The output of the method will allow us to read the image in imageJ i.e. ``imp = IJ.getImage()``.

```
//Function to Open an OMERO image using Bio-Formats
def open_image_plus(host, username, password, group_id, image_id) {
    "Open the image using the Bio-Formats Importer"

    StringBuilder options = new StringBuilder()
    options.append("location=[OMERO] open=[omero:server=")
    options.append(host)
    options.append("\nuser=")
    options.append(username)
    options.append("\nport=")
    options.append(443)
    options.append("\npass=")
    options.append(password)
    options.append("\ngroupID=")
    options.append(group_id)
    options.append("\niid=")
    options.append(image_id)
    options.append("] ")
    options.append("windowless=true view=Hyperstack ")
    IJ.runPlugIn("loci.plugins.LociImporter", options.toString())
}
// Open the Image using Bio-Formats
open_image_plus(HOST, USERNAME, PASSWORD, group_id, String.valueOf(image_id))
imp = IJ.getImage()

```

## Analyze the image

We can run the analysis step-by-step or run the [fiji-macro-segment.ijm](../scripts/fiji-macro-segment.ijm) on the images


The analysis steps are:
 - convert the image into an 8-bit image
 - set the autothreshold to MaxEntropy
 - run the Analyze Particles plugin

One option is to use the step-by-step approach

```
RoiManager.getRoiManager()
IJ.run("8-bit")
//white might be required depending on the version of Fiji
IJ.run(imp, "Auto Threshold", "method=MaxEntropy stack")
IJ.run(imp, "Analyze Particles...", "size=10-Infinity pixel display clear add stack summarize")
IJ.run("Set Measurements...", "area mean standard modal min centroid center perimeter bounding feret's summarize stack display redirect=None decimal=3")  
```

or to use a macro

```
RoiManager.getRoiManager()
IJ.runMacroFile("PATH_TO_MICROFILE/fiji-macro-segment.ijm")
println "Analysis completed"
```

## Convert  and save Regions of Interest (ROIs)
 * Convert the ImageJ ROIs into the OMERO ROIs
 * Save them back to the server


 ```
 def save_rois_to_omero(ctx, image_id, imp) {
    " Save ROI's back to OMERO"
    reader = new ROIReader()
    //convert
    roi_list = reader.readImageJROIFromSources(image_id, imp)
    roi_facility = gateway.getFacility(ROIFacility)
    
    result = roi_facility.saveROIs(ctx, image_id, exp_id, roi_list)
}
```

## Export and save the results as CSV
* Export the results as CSV 
* Save the CSV file back to the server


Export:

```
def save_row(rt, table_rows, image) {
    "Create a summary table of the measurements"
    // Remove the rows not corresponding to the specified channel
    to_delete = new ArrayList()
    
    // We only keep the first channel. Index starts at 1 in ImageJ
    ref = "c:" + 1
    max_bounding_box = 0.0f
    for (i = 0; i < rt.size(); i++) {
        label = rt.getStringValue("Label", i)
        if (label.contains(ref)) {
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
```

Upload:

```
// Upload the results to OMERO
def upload_csv_to_omero(ctx, file, dataset_id) {
    "Upload the CSV file and attach it to the specified dataset"
    svc = gateway.getFacility(DataManagerFacility)
    data = new DatasetData(new DatasetI(dataset_id, false))
    namespace = "training.demo"
    mimetype = "text/csv"
    future = svc.attachFile(ctx, file, mimetype, "", namespace, data)
    future.get()
}

```

## Close the connection

```
// Close the connection
gateway.disconnect()
println "Done"
```

## Let's put it all together

* Open Fiji
* Select ``File>New>Script...``
* Copy the content of the  script [analyse_dataset_save_rois_and_summary_table.groovy](./scripts/analyse_dataset_save_rois_and_summary_table.groovy) or select it from the left-hand pane in the script editor.
* In the script editor, check that the correct progamming is set. For that, select ``Language`` and select ``Groovy``.
* Click the ``run`` button



## Use macro language

The script [analyse_dataset_save_rois_and_summary_table.ijm](./scripts/analyse_dataset_save_rois_and_summary_table.ijm) uses the macro language to perform the same step. 
For the script to work, you will need to install in the Plugins folder of Fiji:
* https://github.com/GReD-Clermont/omero_macro-extensions/releases/download/1.3.2/omero_macro-extensions-1.3.2.jar
* https://github.com/GReD-Clermont/simple-omero-client/releases/download/5.14.0/simple-omero-client-5.14.0.jar

Note that the shapes are not correctly linked to the correct channel in simple-omero-client version 5.14.0.
A [fix](https://github.com/GReD-Clermont/simple-omero-client/pull/65) has been opened.
 


