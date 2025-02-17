package in.pratanumandal.fts.controller;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.server.PathParam;

import org.apache.tomcat.util.http.fileupload.IOUtils;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import in.pratanumandal.fts.bean.SandboxedFile;
import in.pratanumandal.fts.bean.SandboxedFileExtras;
import in.pratanumandal.fts.exception.ForbiddenException;
import in.pratanumandal.fts.exception.InvalidFileNameException;
import in.pratanumandal.fts.exception.ResourceNotFoundException;
import in.pratanumandal.fts.util.CommonUtils;
import in.pratanumandal.fts.util.FtsConstants;
import in.pratanumandal.fts.util.ZipFiles;

@Controller
public class FtsController {
	
	@Autowired
	private Logger logger;
	
	@RequestMapping("/**/{[path:[^\\.]*}")
	public String any() {
		throw new ResourceNotFoundException("The requested path was not found on the server");
	}
	
	@GetMapping("/ping")
	public void ping(HttpServletRequest request, HttpServletResponse response) {
		response.setStatus(200);
	}
	
	@GetMapping("/login")
	public String login(@PathParam("logout") String logout, HttpServletResponse response) {
		
		if (logout != null || 
			(SecurityContextHolder.getContext().getAuthentication() != null &&
			 SecurityContextHolder.getContext().getAuthentication().isAuthenticated() &&
			 !(SecurityContextHolder.getContext().getAuthentication()  instanceof AnonymousAuthenticationToken))) {
			try {
				response.sendRedirect("/");
			} catch (IOException e) {
				logger.error("An error occurred when trying to log in");
				e.printStackTrace();
			}
		}
		
		return "login";
	}
	
	@PreAuthorize("hasAnyRole('ADMIN', 'WRITER', 'READER')")
	@GetMapping("/")
	public String index(@PathParam("path") String path, Map<String, Object> model, HttpServletRequest request, Principal principal) throws IOException {
		
		path = validatePath(path);

		List<SandboxedFile> files = new ArrayList<>();

		File folder = new File(FtsConstants.SANDBOX_FOLDER + "/" + path);
		
		if (!folder.exists()) {
			logger.error("An error occurred when trying to access path: " + path);
			throw new ResourceNotFoundException("The requested folder was not found on the server");
		}
		
		SandboxedFileExtras sandboxedFolder = new SandboxedFileExtras(folder);
		
		File[] listOfFiles = folder.listFiles();
		
		if (listOfFiles == null) {
			logger.error("An error occurred when trying to access path: " + path);
			throw new ForbiddenException("Access Denied");
		}

		Arrays.sort(listOfFiles, (obj1, obj2) -> {
			File file1 = (File) obj1;
			File file2 = (File) obj2;

			if (file1.isDirectory() && !file2.isDirectory()) {
				return -1;
			} else if (!file1.isDirectory() && file2.isDirectory()) {
				return +1;
			} else {
				return file1.compareTo(file2);
			}
		});

		for (File file : listOfFiles) {
			boolean hasAccess = true;
			
			if (file.isDirectory()) {
				if (file.listFiles() == null) {
					hasAccess = false;
				}
			}
			else {
				try (
					FileInputStream fin = new FileInputStream(file);
				){
				} catch (IOException e) {
					hasAccess = false;
				}
			}
			
			if (hasAccess) {
				try {
					SandboxedFile sandboxedFile = new SandboxedFile(file);
					files.add(sandboxedFile);
				} catch (IOException e) {
					logger.error("An error occurred when trying to access path: " + path);
					e.printStackTrace();
				}
			}
		}

		model.put("files", files);
		
		model.put("path", sandboxedFolder.getPath());
		
		model.put("fileCount", sandboxedFolder.getFileCount());
		
		model.put("folderCount", sandboxedFolder.getFolderCount());
		
		model.put("size", sandboxedFolder.getSize());
		
		if (request.isUserInRole("ADMIN")) model.put("admin", true);
		else model.put("admin", false);
		
		if (request.isUserInRole("WRITER")) model.put("writer", true);
		else model.put("writer", false);
		
		if (request.isUserInRole("READER")) model.put("reader", true);
		else model.put("reader", false);
		
		String name = CommonUtils.getNameByUsername(principal.getName());
		model.put("name", name);

		return "index";
	}
	
	@PreAuthorize("hasAnyRole('ADMIN', 'WRITER', 'READER')")
	@GetMapping("/view")
	public void viewFile(@PathParam("path") String path, HttpServletResponse response) throws IOException {
		
		path = validatePath(path);

		File file = new File(FtsConstants.SANDBOX_FOLDER + "/" + path);
		
		SandboxedFileExtras sandboxedFile = new SandboxedFileExtras(file);
		
		ObjectMapper objectMapper = new ObjectMapper();
		String responseString = objectMapper.writeValueAsString(sandboxedFile);
		
		response.setContentType("application/json");
		response.setCharacterEncoding("UTF-8");
		
		PrintWriter out = response.getWriter();
		out.print(responseString);
		out.flush();
		
		response.setStatus(200);
	}

	@PreAuthorize("hasAnyRole('ADMIN', 'WRITER', 'READER')")
	@GetMapping("/download")
	public void getFile(@PathParam("path") String path, HttpServletResponse response) throws IOException {
		
		path = validatePath(path);

		File file = new File(FtsConstants.SANDBOX_FOLDER + "/" + path);
		
		if (!file.exists()) {
			logger.error("An error occurred when trying to access path: " + path);
			throw new ResourceNotFoundException("The requested file or folder was not found on the server");
		}

		if (file.isDirectory()) {
			long length = ZipFiles.getZipLength(file);
			String fileName = file.getName() + ".zip";

			response.setContentType("application/force-download");
			response.setContentLength((int) length);
			response.setHeader("Content-Transfer-Encoding", "binary");
			response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName);
			
			ZipFiles.zipDirectory(file, response.getOutputStream());
			response.flushBuffer();
		} else {
			response.setContentType("application/force-download");
			response.setContentLength((int) file.length());
			response.setHeader("Content-Transfer-Encoding", "binary");
			response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName());

			try {
				FileInputStream is = new FileInputStream(file);
				IOUtils.copy(is, response.getOutputStream());
				response.flushBuffer();
				is.close();
			} catch (IOException ex) {
				logger.error("An error occurred when trying to download file: " + path);
				throw new RuntimeException("IOError writing file to output stream");
			}
		}
	}
	
	@PreAuthorize("hasRole('ADMIN')")
	@DeleteMapping("/delete")
	public void deleteFile(@PathParam("path") String path, HttpServletRequest request, HttpServletResponse response) {
		
		path = validatePath(path);
		
		if (path.equals(new String())) {
			String ipAddress = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest().getRemoteAddr();
			logger.warn("An attempt was made to delete root directory from IP: " + ipAddress);
			throw new ForbiddenException("Deletion of root directory is forbidden");
		}
		
		File file = new File(FtsConstants.SANDBOX_FOLDER + "/" + path);
		
		if (!file.exists()) {
			logger.error("An error occurred when trying to access path: " + path);
			throw new ResourceNotFoundException("The requested file or folder was not found on the server");
		}
		
		try {
			// delete file or directory
			boolean deleted = CommonUtils.deleteFile(file);
			
			if (!deleted) {
				logger.error("An error occurred when trying to delete file/folder: " + path);
				throw new RuntimeException("Failed to delete file/folder: \"" + path + "\"");
			}
		}
		catch (AccessDeniedException e) {
			logger.error("An error occurred when trying to delete file/folder: " + path);
			e.printStackTrace();
			throw new RuntimeException("Failed to delete file/folder: \"" + path + "\"");
		}
		
		response.setStatus(200);
	}

	@PreAuthorize("hasAnyRole('ADMIN', 'WRITER')")
	@PostMapping("/uploadFiles")
	public void fileUpload(@RequestParam("files") List<MultipartFile> files, @RequestParam("fileNames") String fileNames, @RequestParam("path") String path, HttpServletResponse response) {
		
		path = validatePath(path);
		
		String[] fileNameArr = fileNames.split(";", -1);
		
		for (int index = 0; index < files.size(); index++) {
			
			MultipartFile file = files.get(index);
			
			if (!file.isEmpty()) {
				try {
					String fileName;
					File fileObj = new File(file.getOriginalFilename());
					if (fileObj.isAbsolute()) {
						if (index < fileNameArr.length && !fileNameArr[index].isEmpty()) {
							fileName = fileNameArr[index];
						} else {
							fileName = fileObj.getName();
						}
					} else {
						fileName = file.getOriginalFilename();
					}
					fileName = validatePath(fileName);
					Path filePath;
					if (path.isEmpty()) {
						filePath = Paths.get(FtsConstants.SANDBOX_FOLDER + "/" + fileName);
					} else {
						filePath = Paths.get(FtsConstants.SANDBOX_FOLDER + "/" + path + "/" +  fileName);
					}
					Files.createDirectories(filePath.getParent());
					Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					logger.error("An error occurred when trying to upload folder to server");
					e.printStackTrace();
					response.setStatus(500);
					return;
				}
			}
		}
		
		response.setStatus(200);
	}
	
	@PreAuthorize("hasAnyRole('ADMIN', 'WRITER')")
	@PostMapping("/createFolder")
	public void createFolder(@RequestParam("folderName") String folderName, @RequestParam("path") String path, HttpServletResponse response) {
		
		folderName = validatePath(folderName);
		path = validatePath(path);
		
		Path filePath;
		
		try {
			if (path.isEmpty()) {
				filePath = Paths.get(FtsConstants.SANDBOX_FOLDER + "/" + folderName);
			} else {
				filePath = Paths.get(FtsConstants.SANDBOX_FOLDER + "/" + path + "/" +  folderName);
			}
		} catch (InvalidPathException e) {
			logger.error("An error occurred when trying to create folder: " + folderName);
			throw new InvalidFileNameException("Invalid Folder Name: \"" + folderName + "\"");
		}
		
		try {
			Files.createDirectories(filePath);
		} catch (IOException e) {
			logger.error("An error occurred when trying to create folder structure");
			e.printStackTrace();
		}
		
		response.setStatus(200);
	}
	
	private String validatePath(String path) {
		
		if (path != null) {
			path = path.replaceAll("\\\\", "/");
			path = path.replaceAll("/+", "/");
			
			if (path.startsWith("/")) {
				path = path.substring(1);
			}
		
			if (path.equals("/") || path.equals("\\")) {
				path = new String();
			}
		}
		else {
			path = new String();
		}
		
		Pattern pattern = Pattern.compile("((/|\\\\)(\\s)*\\.(\\s)*\\.(\\s)*(/|\\\\))|"			// in between
										 + "(^(\\s)*\\.(\\s)*\\.(\\s)*(/|\\\\))|"				// at beginning
										 + "((/|\\\\)\\.(\\s)*\\.(\\s)*$)|"						// at end
										 + "(^(/|\\\\)*(\\s)*\\.(\\s)*\\.(\\s)*(/|\\\\)*$)");	// only ..
		
		Matcher matcher = pattern.matcher(path);

		if (matcher.find()) {
			String ipAddress = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest().getRemoteAddr();
			logger.warn("An attempt was made to access parent directory from IP: " + ipAddress);
			throw new ForbiddenException("Access to parent directory in path is forbidden");
		}
		
		return path;
	}

}
