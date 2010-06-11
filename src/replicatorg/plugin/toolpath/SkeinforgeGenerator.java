package replicatorg.plugin.toolpath;

import java.awt.Dialog;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import net.miginfocom.swing.MigLayout;

import replicatorg.app.Base;
import replicatorg.app.util.PythonUtils;
import replicatorg.app.util.StreamLoggerThread;
import replicatorg.model.BuildCode;

public class SkeinforgeGenerator extends ToolpathGenerator {

	boolean configSuccess = true;
	String profile = null;
	boolean useRaft = false;
	
	class Profile implements Comparable<Profile> {
		private String fullPath;
		private String name;
		public Profile(String fullPath) {
			this.fullPath = fullPath;
			int idx = fullPath.lastIndexOf(File.separatorChar);
			if (idx >= 0) {
				name = fullPath.substring(idx+1);
			} else {
				name = fullPath;
			}
		}
		public String getFullPath() { return fullPath; }
		public String toString() { return name; }
		public int compareTo(Profile o) { return name.compareTo(o.name); }
	}
	
	List<Profile> getProfiles() {
		List<Profile> profiles = new LinkedList<Profile>();
		// Get default installed profiles
		String dirPath = getSkeinforgePath();
		File dir = new File(dirPath,"prefs");
		if (dir.exists() && dir.isDirectory()) {
			for (String subpath : dir.list()) {
				File subDir = new File(dir,subpath);
				if (subDir.isDirectory()) {
					profiles.add(new Profile(subDir.getAbsolutePath()));
				}
			}
		}
		Collections.sort(profiles);
		return profiles;
	}
	
	class ConfigurationDialog extends JDialog {
		public ConfigurationDialog(JComponent parent) {
			super(SwingUtilities.getWindowAncestor(parent),Dialog.ModalityType.APPLICATION_MODAL);
			setTitle("Choose a skeinforge profile");
			setLayout(new MigLayout());
			
			final JComboBox prefSelection = new JComboBox();
			final String profilePref = "replicatorg.skeinforge.profilePref";
			String profilePath = Base.preferences.get(profilePref,null);
			for (Profile profile : getProfiles()) {
				prefSelection.addItem(profile);
				if (profile.getFullPath().equals(profilePath)) {
					prefSelection.setSelectedItem(profile);
				}
			}
			prefSelection.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					Base.preferences.put(profilePref, ((Profile)prefSelection.getSelectedItem()).getFullPath());
				}
			});
			add(new JLabel("Select a printing profile:"),"wrap");
			add(prefSelection,"growx,wrap");

			final String useRaftPref = "replicatorg.skeinforge.useRaft";
			useRaft = Base.preferences.getBoolean(useRaftPref, false);
			final JCheckBox raftSelection = new JCheckBox("Use raft",useRaft);
			raftSelection.setToolTipText("If this option is checked, skeinforge will lay down a rectangular 'raft' of plastic before starting the build.  "+
					"Rafts increase the build size slightly, so you should avoid using a raft if your build goes to the edge of the platform.");
			raftSelection.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent e) {
					useRaft = raftSelection.isSelected();
					Base.preferences.putBoolean(useRaftPref, useRaft);
				}
			});
			add(raftSelection,"wrap");

//			final JButton newPrefButton = new JButton("Manage printing profiles...");
//			add(newPrefButton,"wrap");

			JButton ok = new JButton("Ok");
			add(ok,"tag ok");
			JButton cancel = new JButton("Cancel");
			add(cancel,"tag cancel");
			ok.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent arg0) {
					configSuccess = true;
					profile = ((Profile)prefSelection.getSelectedItem()).getFullPath();
					setVisible(false);
				}
			});
			cancel.addActionListener(new ActionListener() {
				public void actionPerformed(ActionEvent arg0) {
					configSuccess = false;
					setVisible(false);
				}
			});
		}
		
	};
	public boolean visualConfigure(JComponent parent) {
		// First check for Python.
		boolean hasPython = PythonUtils.interactiveCheckVersion(parent, "Generating gcode",
				new PythonUtils.Version(2,5,0),
				new PythonUtils.Version(3,0,0));
		if (!hasPython) { return false; }
		ConfigurationDialog cd = new ConfigurationDialog(parent);
		double x = parent.getBounds().getCenterX();
		double y = parent.getBounds().getCenterY();
		cd.pack();
		x -= cd.getWidth() / 2.0;
		y -= cd.getHeight() / 2.0;
		cd.setLocation((int)x,(int)y);
		cd.setVisible(true);
		return configSuccess;
	}

	public String getSkeinforgePath() {
	    String skeinforgeDir = System.getProperty("replicatorg.skeinforge.path");
	    if (skeinforgeDir == null || (skeinforgeDir.length() == 0)) {
	    	skeinforgeDir = System.getProperty("user.dir") + File.separator + "skeinforge";
	    }
	    return skeinforgeDir;
	}
	
	public BuildCode generateToolpath() {
		String path = model.getSTLPath();
		
		List<String> arguments = new LinkedList<String>();
		// The -u makes python output unbuffered.  Oh joyous day.
		String[] baseArguments = { "python","-u","skeinforge.py","-p",profile};
		for (String arg : baseArguments) { 
			arguments.add(arg);
		}
		if (useRaft) {
			arguments.add("--raft");
		} else {
			arguments.add("--no-raft");
		}
		arguments.add(path);
		
		ProcessBuilder pb = new ProcessBuilder(arguments);
	    String skeinforgeDir = getSkeinforgePath();
		pb.directory(new File(skeinforgeDir));
		Process process = null;
		try {
			process = pb.start();
			StreamLoggerThread ist = new StreamLoggerThread(process.getInputStream()) {
				@Override
				protected void logMessage(String line) {
					emitUpdate(line);
					super.logMessage(line);
				}
			};
			StreamLoggerThread est = new StreamLoggerThread(process.getErrorStream());
			est.setDefaultLevel(Level.SEVERE);
			ist.setDefaultLevel(Level.FINE);
			ist.start();
			est.start();
			int value = process.waitFor();
			if (value != 0) {
				Base.logger.severe("Unrecognized error code returned by Skeinforge.");
				// Throw ToolpathGeneratorException
				return null;
			}
		} catch (IOException ioe) {
			Base.logger.log(Level.SEVERE, "Could not run skeinforge.", ioe);
			// Throw ToolpathGeneratorException
			return null;
		} catch (InterruptedException e) {
			// We are most likely shutting down, or the process has been manually aborted.  
			// Kill the background process and bail out.
			if (process != null) {
				process.destroy();
			}
			return null;
		}
		int lastIdx = path.lastIndexOf('.'); 
		String root = (lastIdx >= 0)?path.substring(0,lastIdx):path;
		return new BuildCode(root,new File(root+".gcode"));
	}

}
