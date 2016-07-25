/*
 * Copyright 2015 - 2016 i-net software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.inet.gradle.setup.deb;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.internal.file.FileResolver;

import com.inet.gradle.setup.AbstractBuilder;
import com.inet.gradle.setup.DesktopStarter;
import com.inet.gradle.setup.Service;
import com.inet.gradle.setup.SetupBuilder;
import com.inet.gradle.setup.Template;
import com.inet.gradle.setup.deb.DebControlFileBuilder.Script;

public class DebBuilder extends AbstractBuilder<Deb,SetupBuilder> {

    private DebControlFileBuilder  controlBuilder;
    private DebDocumentFileBuilder documentBuilder;
    private SetupBuilder		   setup;

    /**
     * Create a new instance
     * @param deb the calling task
     * @param setup the shared settings
     * @param fileResolver the file Resolver
     */
    public DebBuilder( Deb deb, SetupBuilder setup, FileResolver fileResolver ) {
        super( deb, fileResolver );
        this.setup = setup;
    }

    /**
     * executes all necessary steps from copying to building the Debian package
     */
    public void build() {
        try {
            File filesPath = new File( buildDir, task.getInstallationRoot() );
            task.copyTo( filesPath );
            changeFilePermissionsTo644( filesPath );

            // 	create the package config files in the DEBIAN subfolder

            controlBuilder = new DebControlFileBuilder( super.task, setup, new File( buildDir, "DEBIAN" ) );

            
            addScriptsToControlFiles();
            
            for( Service service : setup.getServices() ) {
                setupService( service );
            }
            
            for( DesktopStarter starter : setup.getDesktopStarters() ) {
                setupStarter( starter );
            }
            
            // TODO: internationalize
            if( setup.getLicenseFile( "en" ) != null ) {
                setupEula();
            }

            
            // removes only the files in the installation path
    		List<String> del_files = setup.getDeleteFiles();
    		for (String file : del_files) {
    			controlBuilder.addTailScriptFragment( Script.PREINST, "if [ -f \"" + task.getInstallationRoot() + "/" + file + "\" ]; then\n  rm -f \""+ task.getInstallationRoot() + "/" + file + "\"\nfi\n" );
    			controlBuilder.addTailScriptFragment( Script.PRERM, "if [ -f \"" + task.getInstallationRoot() + "/" + file + "\" ]; then\n  rm -f \"" + task.getInstallationRoot() + "/" + file + "\"\nfi\n" );
    		}
    		// removes only the folders in the installation path
    		List<String> del_folders = setup.getDeleteFolders();
    		for (String folder : del_folders) {
    			controlBuilder.addTailScriptFragment( Script.PREINST, "rm -R -f \""+ task.getInstallationRoot() + "/" + folder + "\"\n" );
    			controlBuilder.addTailScriptFragment( Script.PRERM, "rm -R -f \"" + task.getInstallationRoot() + "/" + folder + "\"\n" );
    		}
    		
    		DesktopStarter starter = setup.getRunAfter();
    		if(starter != null ) {
    			String executable = starter.getExecutable();
    			String mainClass = starter.getMainClass();
    			String workingDir = starter.getWorkDir();
    			if( executable != null ) {
    				if( workingDir != null ) {
    					controlBuilder.addTailScriptFragment( Script.POSTINST, "( cd \"" + task.getInstallationRoot() + "/" + workingDir + "\" && " + executable + " & )\n" );
    				} else {
    					controlBuilder.addTailScriptFragment( Script.POSTINST, "( cd \"" + task.getInstallationRoot() + "\" && " + executable + " & )\n" );	
    				}
    				
    			} else if( mainClass != null ) {
    				if( workingDir != null ) {
    					controlBuilder.addTailScriptFragment( Script.POSTINST, "( cd \"" + task.getInstallationRoot() + "/" + workingDir + "\" && java -cp " + starter.getMainJar()  + " " +  mainClass + " )\n");
    				} else {
    					controlBuilder.addTailScriptFragment( Script.POSTINST, "( cd \"" + task.getInstallationRoot() + "\" && java -cp \"" + starter.getMainJar()  + "\" " +  mainClass + " )\n");	
    				}
    			}
    		}
    		
    		controlBuilder.addTailScriptFragment( Script.POSTINST, "gtk-update-icon-cache /usr/share/icons/hicolor &>/dev/null || : \n");
            
    		DesktopStarter runBeforeUninstall = setup.getRunBeforeUninstall();
    		if(runBeforeUninstall != null ) {
    			String executable = runBeforeUninstall.getExecutable();
    			String mainClass = runBeforeUninstall.getMainClass();
    			String workingDir = runBeforeUninstall.getWorkDir();
    			controlBuilder.addTailScriptFragment( Script.PRERM, "case \"$1\" in remove|purge)" );    			
    			if( executable != null ) {
    				if( workingDir != null ) {
    					if(task.getDaemonUser().equalsIgnoreCase("root")) {
    						controlBuilder.addTailScriptFragment( Script.PRERM, "( cd \"" + task.getInstallationRoot() + "/" + workingDir + "\" && " + executable + " " + runBeforeUninstall.getStartArguments() + " )" );
    					} else {
    						controlBuilder.addTailScriptFragment( Script.PRERM, "(su " + task.getDaemonUser() + " -c 'cd \"" + task.getInstallationRoot() + "/" + workingDir + "\" && " + executable + " " + runBeforeUninstall.getStartArguments() +  "' )" );
    					}
    				} else {
    					if(task.getDaemonUser().equalsIgnoreCase("root")) {
    						controlBuilder.addTailScriptFragment( Script.PRERM, "( cd \"" + task.getInstallationRoot() + "\" && " + executable + " " + runBeforeUninstall.getStartArguments() +  " )" );
    					} else {
    						controlBuilder.addTailScriptFragment( Script.PRERM, "su " + task.getDaemonUser() + " -c '" + task.getInstallationRoot() + "\" && " + executable + " " + runBeforeUninstall.getStartArguments() +  "' )" );	
    					}
    				}
    				
    			} else if( mainClass != null ) {
    				if( workingDir != null ) {
    					if(task.getDaemonUser().equalsIgnoreCase("root")) {	
    						controlBuilder.addTailScriptFragment( Script.PRERM, "( cd \"" + task.getInstallationRoot() + "/" + workingDir + "\" && java -cp " + runBeforeUninstall.getMainJar()  + " " +  mainClass + " " + runBeforeUninstall.getStartArguments() + ")");
    					} else {
    						controlBuilder.addTailScriptFragment( Script.PRERM, "(su " + task.getDaemonUser() + " -c 'cd \"" + task.getInstallationRoot() + "/" + workingDir + "\" && java -cp " + runBeforeUninstall.getMainJar()  + " " +  mainClass + " " + runBeforeUninstall.getStartArguments() + "' )");
    					}
    					
    				} else {
    					if(task.getDaemonUser().equalsIgnoreCase("root")) {	
    						controlBuilder.addTailScriptFragment( Script.PRERM, "( cd \"" + task.getInstallationRoot() + "\" && java -cp \"" + runBeforeUninstall.getMainJar()  + "\" " +  mainClass + " " + runBeforeUninstall.getStartArguments() + ")");
    					} else {
    						controlBuilder.addTailScriptFragment( Script.PRERM, "(su " + task.getDaemonUser() + " -c 'cd \"" + task.getInstallationRoot() + "\" && java -cp \"" + runBeforeUninstall.getMainJar()  + "\" " +  mainClass + " " + runBeforeUninstall.getStartArguments() + "' )");
    					}
    						
    				}
    			}
    			controlBuilder.addTailScriptFragment( Script.PRERM, "    ;;\nesac" );
    		}
    		
    		
            controlBuilder.build();

            documentBuilder = new DebDocumentFileBuilder( super.task, setup, new File( buildDir, "/usr/share/doc/" + setup.getAppIdentifier() ) );
            documentBuilder.build();

            changeDirectoryPermissionsTo755( buildDir );

            createDebianPackage();

            checkDebianPackage();

        } catch( RuntimeException ex ) {
            throw ex;
        } catch( Exception ex ) {
            throw new RuntimeException( ex );
        }
    }


    /**
     * adds the pre and post step entries to the pre and post config files
     */
    private void addScriptsToControlFiles() {
    	
    	String daemonuser = task.getDaemonUser(); 
        if(!daemonuser.equalsIgnoreCase("root")) {
        	controlBuilder.addTailScriptFragment( Script.POSTINST, "useradd -r -m -s /bin/bash " + daemonuser + " || true \n"
        			+ "\n"
        			+ "chown -R " + daemonuser + ":" + daemonuser + " '" + task.getInstallationRoot() + "'\n"
        			+ "chmod -R g+w '" + task.getInstallationRoot() + "'\n"
        			+ "\n" );

        }
    	
    	
    	ArrayList<String> preinsts = task.getPreinst();
    	for (String preinst : preinsts) {
    		controlBuilder.addTailScriptFragment( Script.PREINST, preinst );	
    	}
    	ArrayList<String> postinsts = task.getPostinst();
		for (String postinst : postinsts) {
			controlBuilder.addTailScriptFragment( Script.POSTINST, postinst );	
		}
    	
		ArrayList<String> prerms = task.getPrerm();
		for (String prerm : prerms) {
			controlBuilder.addTailScriptFragment( Script.PRERM, prerm );	
		}
		ArrayList<String> postrms = task.getPostrm();
		for (String postrm : postrms) {
			controlBuilder.addTailScriptFragment( Script.POSTRM, postrm );	
		}
		
		if(!daemonuser.equalsIgnoreCase("root")) {
			controlBuilder.addTailScriptFragment( Script.POSTRM,   "if [ \"$1\" = \"purge\" ] ; then\n" + 
		            "    userdel -r " + daemonuser + " || true \n" + 
		            "fi" );	
        }
    	
	}

	private void setupEula() throws IOException {
        String templateLicenseName = setup.getAppIdentifier()+"/license";
        String templateAcceptName = setup.getAppIdentifier()+"/accept-license";
        String templateErrorName = setup.getAppIdentifier()+"/error-license";
        try (FileWriter fw = new FileWriter( createFile( "DEBIAN/templates", false ) );
        	// TODO: internationalize
            BufferedReader fr = new BufferedReader( new FileReader( setup.getLicenseFile( "en" ) ) )) {
            fw.write( "Template: " + templateLicenseName + "\n" );
            fw.write( "Type: note\n" );
            fw.write( "Description: License agreement\n" );
            while( fr.ready() ) {
                String line = fr.readLine().trim();
                if( line.isEmpty() ) {
                    fw.write( " .\n" );
                } else {
                    fw.write( ' ' );
                    fw.write( line );
                    fw.write( '\n' );
                }
            }
            fw.write( '\n' );
            fw.write( "Template: " + templateAcceptName + "\n" );
            fw.write( "Type: boolean\n" );
            fw.write( "Description: Do you accept the license agreement?\n" );
            fw.write( "Description-de.UTF-8: Akzeptieren Sie die Lizenzvereinbarung?\n" );
            fw.write( '\n' );
            fw.write( "Template: " + templateErrorName + "\n" );
            fw.write( "Type: error\n" );
            fw.write( "Description: It is required to accept the license to install this package.\n" );
            fw.write( "Description-de.UTF-8: Zur Installation dieser Anwendung müssen Sie die Lizenz akzeptieren.\n" );
            fw.write( '\n' );
        }
        
        controlBuilder.addTailScriptFragment( Script.POSTRM,
                "if [ \"$1\" = \"remove\" ] || [ \"$1\" = \"purge\" ]  ; then\n" + 
                "  db_purge\n"+
                "fi");
        
    	controlBuilder.addHeadScriptFragment( Script.PREINST, 
    	        "if [ \"$1\" = \"install\" ] ; then\n" + 
                "  db_get "+templateAcceptName+"\n" + 
                "  if [ \"$RET\" = \"true\" ]; then\n" + 
                "    echo \"License already accepted\"\n" + 
                "  else\n" + 
                "    db_input high "+templateLicenseName+" || true\n" + 
                "    db_go\n" + 
                "    db_input high "+templateAcceptName+" || true\n" + 
                "    db_go\n" + 
                "    db_get "+templateAcceptName+"\n" + 
                "    if [ \"$RET\" != \"true\" ]; then\n" + 
                "        echo \"License was not accepted by the user\"\n" + 
                "        db_input high "+templateErrorName+" || true\n" + 
                "        db_go\n" + 
                "        db_purge\n" + 
                "        exit 1\n" + 
                "    fi\n" + 
                "  fi\n"+
                "fi");
	}


    /**
     * Creates the files and the corresponding script section for the specified service.
     * @param service the service
     * @throws IOException on errors during creating or writing a file
     */
    private void setupService( Service service ) throws IOException {
    	String workingDir = null;
    	DesktopStarter starter = setup.getRunAfter();
        if(starter != null ) {
        	workingDir = starter.getWorkDir();
        }
        String serviceUnixName = service.getId();
        String installationRoot = task.getInstallationRoot();
        String mainJarPath;
        Template initScript = new Template( "deb/template/init-service.sh" );
        initScript.setPlaceholder( "name", serviceUnixName );
        String version = setup.getVersion();
        initScript.setPlaceholder( "majorversion", version.substring(0, version.indexOf('.')) );
        initScript.setPlaceholder( "displayName", setup.getApplication() );
        initScript.setPlaceholder( "description", service.getDescription() );
        initScript.setPlaceholder( "daemonUser", task.getDaemonUser() );
        initScript.setPlaceholder( "wait", "2" );
        
        if( workingDir != null ) {
			initScript.setPlaceholder( "workdir", installationRoot + "/" + workingDir );
    		mainJarPath = "'" + installationRoot + "/" + workingDir + "/" + service.getMainJar() + "'";
    	} else {	
    		initScript.setPlaceholder( "workdir",  installationRoot );
    		mainJarPath = "'" + installationRoot + "/" + service.getMainJar() + "'";
    	}
                
        initScript.setPlaceholder( "mainJar", mainJarPath );
        initScript.setPlaceholder( "startArguments",
                                   "-cp "+ mainJarPath + " " + service.getMainClass() + " " + service.getStartArguments() );
        String initScriptFile = "etc/init.d/" + serviceUnixName;
        initScript.writeTo( createFile( initScriptFile, true ) );
        
        if(task.getPamConfigurationFile() != null) {
        	File pamFile = new File (task.getPamConfigurationFile());         	
        	File pamDestFile = new File(buildDir.getAbsolutePath(),  "/etc/pam.d/" + pamFile.getName());
        	pamDestFile.mkdirs();
        	Files.copy(pamFile.toPath(), pamDestFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);        	
    	}        
        controlBuilder.addTailScriptFragment( Script.POSTINST, "if [ -f \"/etc/init.d/"+serviceUnixName+"\" ]; then\n  update-rc.d "+serviceUnixName+" defaults 91 09 >/dev/null\nfi" );
        controlBuilder.addTailScriptFragment( Script.POSTINST, "if [ -f \"/etc/init.d/"+serviceUnixName+"\" ]; then\n  service "+serviceUnixName+ " start \nfi");
        controlBuilder.addTailScriptFragment( Script.PRERM,    "if [ -f \"/etc/init.d/"+serviceUnixName+"\" ]; then\n  service "+serviceUnixName+ " stop \nfi");
        controlBuilder.addTailScriptFragment( Script.POSTRM,   "if [ \"$1\" = \"purge\" ] ; then\n" + 
            "    update-rc.d "+serviceUnixName+" remove >/dev/null\n" + 
            "fi" );
    }

    /**
     * Creates the files and the corresponding scripts for the specified desktop starter.
     * @param starter the desktop starter
     * @throws IOException on errors during creating or writing a file
     */
    private void setupStarter( DesktopStarter starter ) throws IOException {
        String unixName = starter.getDisplayName(); 
        String consoleStarterPath = "usr/bin/" + unixName;
        try (FileWriter fw = new FileWriter( createFile( consoleStarterPath, true ) )) {
            fw.write( "#!/bin/bash\n" );
            if(starter.getExecutable() != null) {
            	fw.write( "\"" + task.getInstallationRoot() + "/" + starter.getExecutable() + "\" " + starter.getStartArguments() + " \"$@\"" );
            } else {
            fw.write( "java -cp  \"" + task.getInstallationRoot() + "/" + starter.getMainJar() + "\" " + starter.getMainClass() + " "
                + starter.getStartArguments() + " \"$@\"" );
            }
        }
        int[] iconSizes = { 16, 32, 48, 64, 128 };

        String iconName = "";
        if(starter.getIcons() != null) {
    		iconName = starter.getIcons().toString();
    		int index = iconName.lastIndexOf('/'); 
    		if(index > -1) {
    			iconName = iconName.substring(index+1);
    		}
    		// icons must be png files and should named like that 
    		if(!iconName.endsWith(".png")) {
    			index = iconName.lastIndexOf('.'); 
        		if(index > -1) {
        			iconName = iconName.substring(0,index);
        		}
        		iconName = iconName + ".png";
    		}
        }
        
        for( int size : iconSizes ) {
            File iconDir = new File( buildDir, "usr/share/icons/hicolor/" + size + "x" + size + "/apps/" );
            iconDir.mkdirs();
            File scaledFile = setup.getIconForType( iconDir, "png" + size );
            if( scaledFile != null ) {
            	File iconFile;
            	if(starter.getIcons() != null) {
            		
            		iconFile = new File( iconDir, iconName );
            	} else {
            		iconFile = new File( iconDir, unixName + ".png" );
            	}
                scaledFile.renameTo( iconFile );
                DebUtils.setPermissions( iconFile, false );
            }
        }
        try (FileWriter fw = new FileWriter( createFile( "usr/share/applications/" + unixName + ".desktop", false ) )) {
            fw.write( "[Desktop Entry]\n" );
            fw.write( "Name=" + starter.getDisplayName() + "\n" );
            fw.write( "Comment=" + starter.getDescription().replace( '\n', ' ' ) + "\n" );
            if(starter.getExecutable() != null) {
            	fw.write( "Exec=\"" + task.getInstallationRoot() + "/" + starter.getExecutable() + "\"\n" );
            } else {
            	fw.write( "Exec=\"/" + consoleStarterPath + "\" %F\n" );
            }
            if(starter.getIcons() != null) {
            	fw.write( "Icon=" + iconName + "\n" );
        	} else {
        		fw.write( "Icon=" + unixName + "\n" );
        	}
            fw.write( "Terminal=false\n" );
            fw.write( "StartupNotify=true\n" );
            fw.write( "Type=Application\n" );
            if( starter.getMimeTypes() != null ) {
                fw.write( "MimeType=" + starter.getMimeTypes() + "\n" );
            }
            if( starter.getCategories() != null ) {
                fw.write( "Categories=" + starter.getCategories() + "\n" );
            }
        }
    }

    /**
     * Creates a file in the build path structure.
     * @param path the path relative to the root of the build path
     * @param executable if set to <tt>true</tt> the executable bit will be set in the permission flags
     * @return the created file
     * @throws IOException on errors during creating the file or setting the permissions
     */
    private File createFile( String path, boolean executable ) throws IOException {
        File file = new File( buildDir, path );
        if( !file.getParentFile().exists() ) {
            file.getParentFile().mkdirs();
        }
        file.createNewFile();

        DebUtils.setPermissions( file, executable );
        return file;
    }

    /**
     * execute the lintian tool to check the Debian package This will only be executed if the task 'checkPackage'
     * property is set to true
     */
    private void checkDebianPackage() {
        if( task.getCheckPackage() == null || task.getCheckPackage().equalsIgnoreCase( "true" ) ) {
            ArrayList<String> command = new ArrayList<>();
            command.add( "lintian" );
            //    		command.add( "-d" );
            command.add( task.getSetupFile().getPath() );
            exec( command );
        }
    }

    /**
     * execute the command to generate the Debian package
     */
    private void createDebianPackage() {
        ArrayList<String> command = new ArrayList<>();
        command.add( "fakeroot" );
        command.add( "dpkg-deb" );
        command.add( "--build" );
        command.add( buildDir.getAbsolutePath() );
        command.add( task.getSetupFile().getPath() );
        exec( command );
    }

    /**
     * Changes the permissions of all directories recursively inside the specified path to 755.
     * @param path the path
     * @throws IOException on I/O failures
     */
    private void changeDirectoryPermissionsTo755( File path ) throws IOException {
     	DebUtils.setPermissions( path, true );
        for( File file : path.listFiles() ) {
            if( file.isDirectory() ) {
                changeDirectoryPermissionsTo755( file );
            }
        }
    }

    /**
     * Changes the permissions of all files recursively inside the specified path to 644.
     * @param path the path
     * @throws IOException on I/O failures
     */
    private void changeFilePermissionsTo644( File path ) throws IOException {
        for( File file : path.listFiles() ) {
            if( file.isDirectory() ) {
                changeFilePermissionsTo644( file );
            } else {
            	if( file.getName().endsWith(".sh")  ) {
            		DebUtils.setPermissions( file, true );
            	} else {
            		DebUtils.setPermissions( file, false );
            	}
            }
        }
    }

}
