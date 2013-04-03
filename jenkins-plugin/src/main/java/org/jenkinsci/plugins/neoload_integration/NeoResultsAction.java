package org.jenkinsci.plugins.neoload_integration;

import hudson.model.Action;
import hudson.model.AbstractBuild;
import hudson.model.Run.Artifact;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;

import org.codehaus.plexus.util.FileUtils;

/** This class integrates with the side panel of the specific run of a job. The side panel consists of the navigation links on the left. */
public class NeoResultsAction implements Action {

	/** This tag is found in certain pages generated by NeoLoad. */
	public static final String TAG_HTML_GENERATED_BY_NEOLOAD = "#HTML Report Generated by NeoLoad#";

	/** This is added to a file to mark whether the styles have been applied or not. */
	private static final String COMMENT_APPLIED_STYLE = "<!-- NeoLoad Jenkins plugin applied style -->";

	/** This is added to a file to mark whether the styles have been applied or not. */
	private static final String COMMENT_CSS_APPLIED_STYLE = "/* NeoLoad Jenkins plugin applied style */";

	/** The current build. */
	private final AbstractBuild<?, ?> build;
	
	/** True if the report file is found without any issues. This allows us to only show the link when the report file is found. */
	private Boolean foundReportFile = null;
	
	/** Whether or not to throw certain exceptions. */
	public static boolean throwExceptions = false;

	/** Log various messages. */
	private static final Logger LOGGER = Logger.getLogger(NeoResultsAction.class.getName());
	
	/** @param target */
	public NeoResultsAction(final AbstractBuild<?, ?> target) {
		super();
		this.build = target;
	}
	
    /**
     * @param build
     */
    public static void addActionIfNotExists(AbstractBuild<?, ?> build) {
    	boolean alreadyAdded = false;
    	for (Action a: build.getActions()) {
    		if (a instanceof NeoResultsAction) {
    			alreadyAdded = true;
    			break;
    		}
    	}
    	
    	if (!alreadyAdded) {
    		NeoResultsAction nra = new NeoResultsAction(build);
    		build.addAction(nra);
    		LOGGER.log(Level.FINE, "Added Performance Result action to build " + build.number + " of job " + 
    				build.getProject().getDisplayName());
    	}
    }
	
	/** For storing artifact data. */
	static class FileAndContent {
		/** Artifact data. */
		private final File file;

		/** URL to the artifact in Jenkins. */
		private final String href;

		/** Artifact data. */
		private String content = null;

		/** Constructor.
		 * 
		 * @param file
		 * @param href
		 * @param content
		 */
		public FileAndContent(File file, String href, String content) {
			this.file = file;
			this.href = href;
			this.content = content;
		}
	}

	/**
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("rawtypes")
	private FileAndContent findHtmlReportArtifact() {
		Artifact artifact = null;
		Iterator<?> it = build.getArtifacts().iterator();
		String content = null;
		FileAndContent ac = null;

		// remove files that don't match
		while (it.hasNext()) {
			artifact = (Artifact) it.next();

			// if it's an html file
			if ((artifact.getFileName().length() > 4) && 
					("html".equalsIgnoreCase(artifact.getFileName().substring(artifact.getFileName().length() - 4)))) {

				// verify file contents
				content = null;
				try {
					content = FileUtils.fileRead(artifact.getFile().getAbsolutePath());
					if ((content != null) && (isNeoLoadHTMLReport(content))) {
						// verify that the file was created during the current build
						if (isFromTheCurrentBuild(artifact)) {
							ac = new FileAndContent(artifact.getFile(), artifact.getHref(), content);
							break;
						}
						LOGGER.log(Level.FINE, 
								"Build " + build.number + ", Found " + artifact.getFileName() + ", but it's too old to use.");					
					}
				} catch (Exception e) {
					LOGGER.log(Level.FINE, "Error reading file. " + e.getMessage(), e);
					if (throwExceptions) {
						throw new RuntimeException(e);
					}
				}
			}
		}

		return ac;
	}

	/**
	 * @param content2 
	 * @param artifact
	 * @return
	 * @throws IOException 
	 * @throws InterruptedException 
	 */
	private boolean isFromTheCurrentBuild(Artifact artifact) throws IOException, InterruptedException {
		// Look at the date of the file on the workspace, not the artifact file. The artifat file is always new because it is 
		// copied after the job is run. 
		
		String workspaceFilePath = build.getWorkspace().toURI().getPath() + File.separatorChar + artifact.relativePath;
		File f = new File(workspaceFilePath);
		
		// get the date of the report
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd kk:mm:ss");
		Calendar buildStartTime = build.getTimestamp();
		Calendar artifactCreateTime = Calendar.getInstance();
		artifactCreateTime.setTime(new Date(f.lastModified()));
		
		LOGGER.log(Level.FINE, "Build start time: " + sdf.format(buildStartTime.getTime()) + ", Artifact file time: " + 
				sdf.format(artifactCreateTime.getTime()) + ", Artifact file: " + f.getAbsolutePath() + 
				", original file: " + f.getAbsolutePath());
		
		if (buildStartTime.before(artifactCreateTime)) {
			return true;
		}
		
		return false;
	}

	/**
	 * @param content
	 * @return true if the passed in content is the html file of a NeoLoad generated report
	 */
	private static boolean isNeoLoadHTMLReport(String content) {
		if (content.contains(TAG_HTML_GENERATED_BY_NEOLOAD)) {
			return true;
		}
		
		// we could be dealing with an old version of NeoLoad, so we look for a likely NeoLoad file.
		if ((content.contains("<title>Rapport de test de performance</title>")) ||
				(content.contains("<title>Performance Testing Report</title>"))) {
			
			if (content.startsWith("<html") && 
				(content.contains("<frameset")) && 
				(content.contains("/menu.html")) &&
				(content.contains("/summary.html"))) {
				
				return true;
			}
		}
		
		return false;
	}

	/**
	 * @return
	 */
	public AbstractBuild<?, ?> getBuild() {
		return build;
	}

	/**
	 * @return
	 * @throws IOException
	 */
	public String getHtmlReportFilePath() {
		FileAndContent ac = findHtmlReportArtifact();

		if (ac != null) {
			// append the style changes if it hasn't already been done
			if (!ac.content.contains(COMMENT_APPLIED_STYLE)) {
				applySpecialFormatting(ac);
			}

			foundReportFile = true;
			return ac.href;
		}

		foundReportFile = false;
		return null;
	}

	/**
	 * @param ac
	 * @throws IOException
	 */
	private static void applySpecialFormatting(FileAndContent ac) {
		try {
			// adjust the content
			ac.content = ac.content.replaceAll(Matcher.quoteReplacement("id=\"menu\""), "id=\"menu\" style='overflow-x: hidden;' ");
			ac.content = ac.content.replaceAll(Matcher.quoteReplacement("id=\"content\""), "id=\"content\" style='overflow-x: hidden;' ");
			ac.content += COMMENT_APPLIED_STYLE;

			// write the content
			long modDate = ac.file.lastModified();
			if (ac.file.canWrite()) {
				ac.file.delete();
				FileUtils.fileWrite(ac.file.getAbsolutePath(), ac.content);
				ac.file.setLastModified(modDate); // keep the old modification date
			}

			// find the menu.html
			String temp = ac.content.substring(ac.content.indexOf("src=\"") + 5);
			temp = temp.substring(0, temp.indexOf('\"'));
			String menuLink = ac.file.getParent() + File.separatorChar + temp;
			String menuContent = FileUtils.fileRead(menuLink);
			menuContent = menuContent.replace(Matcher.quoteReplacement("body {"), "body {\noverflow-x: hidden;");
			menuContent += COMMENT_APPLIED_STYLE;
			new File(menuLink).delete();
			FileUtils.fileWrite(menuLink, menuContent);

			// find the style.css
			temp = ac.content.substring(ac.content.indexOf("<link"), ac.content.indexOf(">", ac.content.indexOf("<link")));
			temp = temp.substring(temp.indexOf("href=") + 6, temp.length() - 1);
			String styleLink = ac.file.getParent() + File.separatorChar + temp;
			String styleContent = FileUtils.fileRead(styleLink);
			styleContent = styleContent.replace(Matcher.quoteReplacement("body {"), "body {\noverflow-x: hidden;");
			styleContent += COMMENT_CSS_APPLIED_STYLE;
			new File(styleLink).delete();
			FileUtils.fileWrite(styleLink, styleContent);

		} catch (IOException e) {
			// this operation is not important enough to throw an exception.
			LOGGER.log(Level.WARNING, "Couldn't add custom style to report files.");
		}
	}

	public String getDisplayName() {
		setFoundReportFile();
		if (!foundReportFile) {
			return null;
		}
		return "Performance Result";
	}

	public String getIconFileName() {
		setFoundReportFile();
		if (!foundReportFile) {
			return null;
		}
		return "/plugin/neoload-hudson-plugin/images/neoload-cropped.png";
	}

	public String getUrlName() {
		setFoundReportFile();
		if (!foundReportFile) {
			return null;
		}
		return "neoload-report";
	}

	/** Set true if we can find the report file. */
	private void setFoundReportFile() {
		if (foundReportFile == null) {
			getHtmlReportFilePath();
		}
	}
}
