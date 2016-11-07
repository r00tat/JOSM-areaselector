// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.plugins.areaselector;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import javax.swing.JOptionPane;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openstreetmap.josm.Main;
import org.openstreetmap.josm.actions.MergeNodesAction;
import org.openstreetmap.josm.actions.mapmode.MapMode;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.PseudoCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapFrame;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.tools.ImageProvider;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * @author Paul Woelfel
 *
 */
public class AreaSelectorAction extends MapMode implements MouseListener {

    protected int colorThreshold = ImageAnalyzer.DEFAULT_COLORTHRESHOLD, thinningIterations = ImageAnalyzer.DEFAULT_THINNING_ITERATIONS;
    protected double toleranceDist = ImageAnalyzer.DEFAULT_TOLERANCEDIST, toleranceAngle = ImageAnalyzer.DEFAULT_TOLERANCEANGLE;

    protected boolean showAddressDialog = true, mergeNodes = true;

    public static final String PLUGIN_NAME = "areaselector";

    public static final String
    KEY_SHOWADDRESSDIALOG = "showaddressdialog",
    KEY_MERGENODES = "mergenodes",
    PREF_KEYS = PLUGIN_NAME+".keys",
    PREF_VALUES = PLUGIN_NAME + ".values";


    protected Logger log = LogManager.getLogger(AreaSelectorAction.class.getCanonicalName());

    protected Point clickPoint = null;

    protected HashMap<String, String> prefs = null;

    public AreaSelectorAction(MapFrame mapFrame) {
        super(tr("Area Selection"), "areaselector", tr("Select an area (e.g. building) from an underlying image."),
                Shortcut.registerShortcut("tools:areaselector",
                        tr("Tools: {0}", tr("Area Selector")), KeyEvent.VK_A, Shortcut.ALT_CTRL), mapFrame, getCursor());

        // load prefs
        this.readPrefs();
    }

    protected void readPrefs() {
        String[] keys = Main.pref.getCollection(PREF_KEYS).toArray(new String[0]);
        String[] values = Main.pref.getCollection(PREF_VALUES).toArray(new String[0]);
        prefs = new HashMap<>();
        if (keys == null || values == null || keys.length != values.length) {
            //use default prefs
        } else {
            for (int i = 0; i < keys.length; i++) {
                prefs.put(keys[i], values[i]);
            }
        }

        if (prefs.containsKey(KEY_MERGENODES)) {
            this.mergeNodes = prefs.get(KEY_MERGENODES).compareTo("true") == 0;
        }
        if (prefs.containsKey(KEY_SHOWADDRESSDIALOG)) {
            this.showAddressDialog = prefs.get(KEY_SHOWADDRESSDIALOG).compareTo("true") == 0;
        }
    }

    private static Cursor getCursor() {
        return ImageProvider.getCursor("crosshair", "areaselector");
    }

    @Override
    public void enterMode() {
        if (!isEnabled()) {
            return;
        }
        super.enterMode();
        Main.map.mapView.setCursor(getCursor());
        Main.map.mapView.addMouseListener(this);
    }

    @Override
    public void exitMode() {
        super.exitMode();
        Main.map.mapView.removeMouseListener(this);
    }

    public void updateMapFrame(MapFrame oldFrame, MapFrame newFrame) {
        // or not, we just use Main to get the current mapFrame
    }

    /**
     * Invoked when the mouse button has been clicked (pressed and released) on a component.
     */
    @Override
    public void mouseClicked(MouseEvent e) {

        log.info("mouse clicked " + e);

        if (!Main.map.mapView.isActiveLayerDrawable()) {
            return;
        }
        requestFocusInMapView();
        updateKeyModifiers(e);
        if (e.getButton() == MouseEvent.BUTTON1) {
            try {
                clickPoint = e.getPoint();
                createArea();
            } catch (Exception ex) {
                log.error("failed to add area", ex);
                new BugReportDialog(ex);
            }
        }
    }

    public BufferedImage getLayeredImage() {
        MapView mapView = Main.map.mapView;

        BufferedImage bufImage = new BufferedImage(mapView.getWidth(), mapView.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D imgGraphics = bufImage.createGraphics();

        Layer[] layers = mapView.getLayerManager().getLayers().toArray(new Layer[0]);

        for (int i = layers.length-1; i >= 0; i--) {
            Layer layer = layers[i];
            if (layer.isVisible() && layer.isBackgroundLayer()) {
                Composite translucent = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) layer.getOpacity());
                imgGraphics.setComposite(translucent);
                layer.paint(imgGraphics, mapView, mapView.getRealBounds());
            }
        }

        return bufImage;
    }

    public void createArea() {

        MapView mapView = Main.map.mapView;

        BufferedImage bufImage = getLayeredImage();

        ImageAnalyzer imgAnalyzer = new ImageAnalyzer(bufImage, clickPoint, prefs);

		// adjust distance to pixel instead of meters
		double distMeters = ImageAnalyzer.DEFAULT_TOLERANCEDIST;
		if(prefs.containsKey(ImageAnalyzer.KEY_TOLERANCEDIST)){
			try {
				distMeters = Double.parseDouble(prefs.get(ImageAnalyzer.KEY_TOLERANCEDIST));
			} catch (NumberFormatException ex) {
				Main.debug(ex);
			}
		}
		double toleranceInPixel = distMeters * 100 / mapView.getDist100Pixel();
		// log.info("tolerance in m: "+distMeters + " in pixel: "+toleranceInPixel + " 100px in m: "+mapView.getDist100Pixel());
		imgAnalyzer.setToleranceDist(toleranceInPixel);

        Polygon polygon = imgAnalyzer.getArea();

        if (polygon != null) {
            Way way = createWayFromPolygon(mapView, polygon);

            way.put(AddressDialog.TAG_BUILDING, "yes");


            Collection<Command> cmds = new LinkedList<>();
            List<Node> nodes = way.getNodes();
            for (int i = 0; i < nodes.size() - 1; i++) {

                cmds.add(new AddCommand(nodes.get(i)));
            }
            cmds.add(new AddCommand(way));

            Command c = new SequenceCommand(/* I18n: Name of command */ tr("Created area"), cmds);
            Main.main.undoRedo.add(c);
            Main.getLayerManager().getEditDataSet().setSelected(way);

            if (mergeNodes) {
                mergeNodes(way);
            }

            if (showAddressDialog) {
                showAddressDialog(way);
            }
        } else {
            JOptionPane.showMessageDialog(Main.map,
                    tr("Unable to detect a polygon where you clicked."), tr("Area Selector"), JOptionPane.WARNING_MESSAGE);
        }
    }

    public OsmPrimitive showAddressDialog(Way way) {
        return new AddressDialog(way).showAndSave();
    }

    public Way createWayFromPolygon(MapView mapView, Polygon polygon) {
        Way way = new Way();

        Node firstNode = null;
        for (int i = 0; i < polygon.npoints; i++) {
            Node node = new Node(mapView.getLatLon(polygon.xpoints[i], polygon.ypoints[i]));
            if (firstNode == null) {
                firstNode = node;
            }
            way.addNode(node);
        }

        if (polygon.npoints > 1 && firstNode != null) {
            way.addNode(firstNode);
        }
        return way;
    }


    /**
     * Merge Nodes on way to existing nodes
     */
    public Way mergeNodes(Way way) {
        List<Node> deletedNodes = new ArrayList<>();
        for (int i = 0; i < way.getNodesCount(); i++) {
            Node node = way.getNode(i);

            List<Node> selectedNodes = new ArrayList<>();
            selectedNodes.add(node);
            List<Node> nearestNodes = Main.map.mapView.getNearestNodes(
                    Main.map.mapView.getPoint(selectedNodes.get(0)), selectedNodes, OsmPrimitive::isUsable);

            for (Node n: nearestNodes) {
                if (!way.containsNode(n) && !deletedNodes.contains(n)) {
                    selectedNodes.add(n);
                }
            }
            if (selectedNodes.size() > 1) {
                Node targetNode = MergeNodesAction.selectTargetNode(selectedNodes);
                Node targetLocationNode = MergeNodesAction.selectTargetLocationNode(selectedNodes);
                Command c = MergeNodesAction.mergeNodes(Main.getLayerManager().getEditLayer(), selectedNodes, targetNode, targetLocationNode);

                if (c != null) {
                    Main.main.undoRedo.add(c);
                    for (PseudoCommand subCommand : c.getChildren()) {
                        if (subCommand instanceof DeleteCommand) {
                            DeleteCommand dc = (DeleteCommand) subCommand;
                            // check if a deleted node is in the way
                            for (OsmPrimitive p: dc.getParticipatingPrimitives()) {
                                if (p instanceof Node) {
                                    deletedNodes.add((Node) p);
                                }
                            }
                        }
                    }
                }
            }
        }

        return (Way) Main.getLayerManager().getEditDataSet().getPrimitiveById(way.getId(), OsmPrimitiveType.WAY);
    }

    /**
     * merge node with neighbor nodes
     * @param node node to merge
     * @return true if node was merged
     */
    public Command mergeNode(Node node) {
        List<Node> selectedNodes = new ArrayList<>();
        selectedNodes.add(node);
        List<Node> nearestNodes = Main.map.mapView.getNearestNodes(
                Main.map.mapView.getPoint(selectedNodes.get(0)), selectedNodes, OsmPrimitive::isUsable);
        selectedNodes.addAll(nearestNodes);
        Node targetNode = MergeNodesAction.selectTargetNode(selectedNodes);
        Node targetLocationNode = MergeNodesAction.selectTargetLocationNode(selectedNodes);
        return MergeNodesAction.mergeNodes(Main.getLayerManager().getEditLayer(), selectedNodes, targetNode, targetLocationNode);
    }

    /**
     * @return the prefs
     */
    public HashMap<String, String> getPrefs() {
        return prefs;
    }

    /**
     * @param prefs the prefs to set
     */
    public void setPrefs(HashMap<String, String> prefs) {
        this.prefs = prefs;
        Main.pref.putCollection(PREF_KEYS, prefs.keySet());
        Main.pref.putCollection(PREF_VALUES, prefs.values());
        this.readPrefs();
    }
}
