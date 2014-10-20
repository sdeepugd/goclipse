package com.googlecode.goclipse.builder;

import java.io.File;

import melnorme.utilbox.collections.ArrayList2;
import melnorme.utilbox.core.CoreUtil;
import melnorme.utilbox.misc.MiscUtil;
import melnorme.utilbox.process.ExternalProcessHelper.ExternalProcessResult;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.QualifiedName;

import com.googlecode.goclipse.Activator;
import com.googlecode.goclipse.Environment;
import com.googlecode.goclipse.core.GoEnvironmentPrefs;
import com.googlecode.goclipse.core.GoProjectEnvironment;
import com.googlecode.goclipse.core.GoProjectPrefConstants;
import com.googlecode.goclipse.core.operations.GoToolManager;
import com.googlecode.goclipse.core.tools.GoBuildOuputProcessor;
import com.googlecode.goclipse.tooling.GoCommandConstants;
import com.googlecode.goclipse.tooling.GoFileNaming;
import com.googlecode.goclipse.tooling.env.GoEnvironment;

/**
 * GoCompiler provides the GoClipse interface to the go build tool.
 * 
 * @author ??? - Original author?
 * @author Bruno - cleanup refactoring of external tool calling
 */
public class GoCompiler {

	private static final QualifiedName	COMPILER_VERSION_QN	= new QualifiedName(Activator.PLUGIN_ID, "compilerVersion");
	
	protected final IProject project;
	protected final GoEnvironment goEnv;
	
	private String version;
	private long versionLastUpdated	= 0;
		
	public GoCompiler(IProject project) {
		this.project = project;
		this.goEnv = GoProjectEnvironment.getGoEnvironment(project);
	}
	
	public IProject getProject() {
		return project;
	}
	

	/**
	 * @param project
	 * @param pmonitor
	 * @param fileList
	 */
	public void compileCmd(IProgressMonitor pmonitor, java.io.File target) 
			throws CoreException {
		
		final IPath  projectLocation = project.getLocation();
		final IFile  file            = project.getFile(target.getAbsolutePath().replace(projectLocation.toOSString(), ""));
		final IPath  binFolderPath       = GoProjectEnvironment.getBinFolder(project); 
		
		final String compilerPath = GoEnvironmentPrefs.COMPILER_PATH.get();
		final String outExtension = (MiscUtil.OS_IS_WINDOWS ? ".exe" : "");

			// the path exist to find the cc
			String   outPath = null;
			ArrayList2<String> cmd = new ArrayList2<>();
			
			MarkerUtilities.deleteFileMarkers(file);
			if(Environment.isCmdSrcFolder(project, (IFolder)file.getParent())){
				outPath = binFolderPath.append(target.getName().replace(GoFileNaming.GO_SOURCE_FILE_EXTENSION, outExtension)).toOSString();
				cmd.addElements(
					compilerPath,
					GoCommandConstants.GO_BUILD_COMMAND
				).addElements(
					GoProjectPrefConstants.GO_BUILD_EXTRA_OPTIONS.getParsedArguments(project)
				).addElements(
					GoCommandConstants.COMPILER_OPTION_O,
					outPath, 
					file.getName() 					
				);
			} else {
				MarkerUtilities.deleteFileMarkers(file.getParent());
				outPath = binFolderPath.append(target.getParentFile().getName() + outExtension).toOSString();
				cmd.addElements(
					compilerPath,
					GoCommandConstants.GO_BUILD_COMMAND
				).addElements(
					GoProjectPrefConstants.GO_BUILD_EXTRA_OPTIONS.getParsedArguments(project)
				).addElements(
					GoCommandConstants.COMPILER_OPTION_O,
					outPath 
				);
			}
			
			ExternalProcessResult processResult = GoToolManager.getDefault().runBuildTool(goEnv, project, pmonitor, 
				target.getParentFile(), cmd);
			
			refreshProject(project, pmonitor);
			int errorCount = 0;
			
			StreamAsLines sal = new StreamAsLines(processResult);
			
			final String pkgPath = target.getParentFile().getAbsolutePath().replace(projectLocation.toOSString(), "");
			if (sal.getLines().size() > 0) {
		    	errorCount = processCompileOutput(sal, pkgPath, file);
		    }
		
	}

	/**
	 * @param project
	 * @param pmonitor
	 * @param fileList
	 */
	public void compileAll(IProgressMonitor pmonitor, IFolder target) throws CoreException {
		
		final String compilerPath = GoEnvironmentPrefs.COMPILER_PATH.get();

			ArrayList2<String> cmd = new ArrayList2<>();
			
			MarkerUtilities.deleteFileMarkers(target);
			cmd.addElements(
				compilerPath,
				GoCommandConstants.GO_BUILD_COMMAND
			).addElements(
				GoProjectPrefConstants.GO_BUILD_EXTRA_OPTIONS.getParsedArguments(project)
			);
			
			File file = new File(target.getLocation().toOSString());
			
			ExternalProcessResult processResult = GoToolManager.getDefault().runBuildTool(goEnv, project, pmonitor, 
				file, cmd);


			refreshProject(project, pmonitor);
		    StreamAsLines sal = new StreamAsLines(processResult);
		    
		    String currentPackage = "";
		    for ( String line : sal.getLines()) {
		    	System.out.println(line);
		    	if(line.startsWith("#")) {
		    		currentPackage = line.replace("#", "").trim();
		    		continue;
		    	}
		    	String[] elements = line.split(":");
		    	String message = line.substring(elements[0].length()+elements[1].length()+2);
		    	String path = target.getName()+File.separator+elements[0];
		    	IResource res = project.findMember(path);
		    	MarkerUtilities.addMarker(res, Integer.parseInt(elements[1]), message, IMarker.SEVERITY_ERROR);
		    }

	}
	
	
	/**
	 * @param project
	 * @param pmonitor
	 * @param fileList
	 */
	public void compilePkg(IProgressMonitor pmonitor, final String pkgpath, java.io.File target) throws CoreException {
		
		final String           compilerPath    = GoEnvironmentPrefs.COMPILER_PATH.get();
		
		final IPath  projectLocation = project.getLocation();
		final IFile  file            = project.getFile(target.getAbsolutePath().replace(projectLocation.toOSString(), ""));
		
		String         pkgPath    = null;
		File           workingDir = null;
		
		if (target.isFile()) {
			pkgPath = target.getParentFile().getAbsolutePath().replace(
					projectLocation.toOSString(), "");
			workingDir = target.getParentFile();
			
		} else {
			pkgPath = target.getAbsolutePath().replace(
					projectLocation.toOSString(), "");
			workingDir = target;
		}
		
			ArrayList2<String> cmd = new ArrayList2<String>(
				compilerPath,
				GoCommandConstants.GO_BUILD_COMMAND,
				GoCommandConstants.COMPILER_OPTION_O,
				pkgpath,
				"."
			);
			
			
			ExternalProcessResult processResult = GoToolManager.getDefault().runBuildTool(goEnv, project, pmonitor, 
				workingDir, cmd);

		    refreshProject(project, pmonitor);
			clearPackageErrorMessages(project, pkgPath);
			int errorCount = 0;
			StreamAsLines sal = new StreamAsLines(processResult);
			
			if (sal.getLines().size() > 0) {
		    	errorCount = processCompileOutput(sal, pkgPath, file);
		    }
			
			String  goPath  = goEnv.getGoPathString();
			String goroot = goEnv.getGoPathString();
			
			GoTestRunner.scheduleTest(project, compilerPath, file, pkgPath,
					workingDir, goPath, goroot, errorCount);

	}

	

	/**
	 * @param project
	 * @param pmonitor
	 * @param fileList
	 */
	public void installAll(IProgressMonitor pmonitor) throws CoreException {
		
		//final IFile  file            = project.getFile(target.getAbsolutePath().replace(projectLocation.toOSString(), ""));
		//final String pkgPath         = target.getParentFile().getAbsolutePath().replace(projectLocation.toOSString(), "");

		final String           compilerPath    = GoEnvironmentPrefs.COMPILER_PATH.get();
		
			ArrayList2<String> cmd = new ArrayList2<>(compilerPath, GoCommandConstants.GO_INSTALL_COMMAND, "all");

			ExternalProcessResult processResult = GoToolManager.getDefault().runBuildTool(goEnv, project, pmonitor, 
				project.getLocation().toFile(), cmd);
			
			refreshProject(project, pmonitor);
			
			StreamAsLines sal = new StreamAsLines(processResult);
			
			//clearPackageErrorMessages(project, pkgPath);
			int errorCount = 0;
		    if (sal.getLines().size() > 0) {
		    	errorCount = processCompileOutput(sal, null, null);
		    }

	}
	
	

    private void refreshProject(final IProject project, IProgressMonitor pmonitor) throws CoreException {
        project.refreshLocal(IResource.DEPTH_INFINITE, pmonitor);
    }

	/**
     * @param project
     * @param pkgPath
     */
    private void clearPackageErrorMessages(final IProject project, final String pkgPath) {
	    IFolder folder = project.getFolder(pkgPath);
	    try {
	        for (IResource res:folder.members()){
	        	if(res instanceof IFile){
	        		MarkerUtilities.deleteFileMarkers(res);
	        	}
	        }
	    } catch (CoreException e) {
	        Activator.logError(e);
	    }
    }

	public String getVersion() throws CoreException {
		// The getCompilerVersion() call takes about ~30 msec to compute.
		// Because it's expensive,
		// we cache the value for a short time.
		final long TEN_SECONDS = 10 * 1000;

		if ((System.currentTimeMillis() - versionLastUpdated) > TEN_SECONDS) {
			version = getCompilerVersion();
			versionLastUpdated = System.currentTimeMillis();
		}

		return version;
	}

	/**
	 * 
	 * @param project
	 * @param dependencies
	 * @return
	 */
	private boolean dependenciesExist(String[] dependencies) {
		ArrayList2<String> sourceDependencies = new ArrayList2<String>();
		for (String dependency : dependencies) {
			if (dependency.endsWith(GoFileNaming.GO_SOURCE_FILE_EXTENSION)) {
				sourceDependencies.add(dependency);
			}
		}
		return GoBuilder.dependenciesExist(project, sourceDependencies.toArray(new String[] {}));
	}

	/**
	 * @param project
	 */
	public void updateVersion(IProject project) {
		try {
			project.setPersistentProperty(COMPILER_VERSION_QN, getVersion());
		} catch (CoreException ex) {
			Activator.logError(ex);
		}
	}

	/**
	 * @param project
	 * @return
	 */
	public boolean requiresRebuild(IProject project) throws CoreException {
		String storedVersion;

		try {
			storedVersion = project.getPersistentProperty(COMPILER_VERSION_QN);
		} catch (CoreException ex) {
			storedVersion = null;
		}

		String currentVersion = getVersion();
		
		if (currentVersion == null) {
			// We were not able to get the latest compiler version - don't force
			// a rebuild.
			return false;
		} else {
			return !CoreUtil.areEqual(storedVersion, currentVersion);
		}
	}
	
	private int processCompileOutput( final StreamAsLines output,
            final String        relativeTargetDir,
            final IFile         file) {
		return GoBuildOuputProcessor.processCompileOutput(project, output, relativeTargetDir, file);
	}
	/**
	 * Returns the current compiler version (ex. "6g version release.r58 8787").
	 * Returns null if we were unable to recover the compiler version. The
	 * returned string should be treated as an opaque token.
	 * 
	 * @return the current compiler version
	 */
	public static String getCompilerVersion() throws CoreException {
		
		String version = null;
		String compilerPath = GoEnvironmentPrefs.COMPILER_PATH.get();

		if (compilerPath == null || compilerPath.length() == 0) {
			return null;
		}

			ArrayList2<String> cmd = new ArrayList2<String>(compilerPath, GoCommandConstants.GO_VERSION_COMMAND);

			ProcessBuilder pb = new ProcessBuilder(cmd).directory(null);
			
			ExternalProcessResult processResult = GoToolManager.getDefault().runTool(
				null, new NullProgressMonitor(), pb);
			
			StreamAsLines output = new StreamAsLines(processResult);

			final String GO_VERSION = "go version ";

			for (String line : output.getLines()) {
				if (line != null && line.startsWith(GO_VERSION)) {
					version = line.substring(GO_VERSION.length());
					break;
				}
			}

		return version;
	}
	
}