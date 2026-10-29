// Vietnamese is the default UI language; English is the alternative (NFR-15).
const DICT = {
  vi: {
    projects: "Dự án",
    noProjects: "Chưa có dự án nào.",
    addProject: "Thêm dự án",
    name: "Tên dự án",
    color: "Màu",
    dataRoot: "Folder dữ liệu (đường dẫn trên máy)",
    dataRootHint: "Dữ liệu dự án nằm ở <folder>/<tên>, tách khỏi source code.",
    repos: "Repo",
    repoPath: "Đường dẫn repo",
    baseBranch: "Nhánh nền",
    addRepo: "+ Thêm repo",
    remove: "Xoá",
    initGit: "Khởi tạo folder dữ liệu thành git repo",
    create: "Tạo dự án",
    open: "Mở",
    unregister: "Bỏ đăng ký",
    confirmUnregister: "Bỏ đăng ký dự án {name}? Folder dữ liệu được giữ nguyên.",
    services: "Service",
    noServices: "Chưa phát hiện service nào.",
    dataDir: "Folder dữ liệu",
    tasks: "Task",
    noTasks: "Chưa có ticket.",
    overview: "Tổng quan",
    projectConfig: "Cấu hình dự án",
    ticketCode: "Mã ticket",
    ticketManual: "PMO nhập, để trống thì tự sinh",
    ticketAuto: "Luôn tự sinh",
    ticketPattern: "Regex kiểm mã ticket (tuỳ chọn)",
    branchPatterns: "Mẫu branch",
    environments: "Môi trường test (cách nhau bởi dấu phẩy)",
    detectServices: "Phát hiện service",
    addService: "+ Thêm service",
    servicePath: "Thư mục trong repo",
    kind: "Loại",
    save: "Lưu",
    saved: "Đã lưu.",
    notFound: "Không tìm thấy dự án.",
    language: "Ngôn ngữ",
    status: "Thông số"
  },
  en: {
    projects: "Projects",
    noProjects: "No projects yet.",
    addProject: "Add project",
    name: "Project name",
    color: "Color",
    dataRoot: "Data folder (local path)",
    dataRootHint: "Project data lives in <folder>/<name>, separate from source code.",
    repos: "Repos",
    repoPath: "Repo path",
    baseBranch: "Base branch",
    addRepo: "+ Add repo",
    remove: "Remove",
    initGit: "Initialize the data folder as a git repo",
    create: "Create project",
    open: "Open",
    unregister: "Unregister",
    confirmUnregister: "Unregister project {name}? The data folder is kept.",
    services: "Services",
    noServices: "No services detected.",
    dataDir: "Data folder",
    tasks: "Tasks",
    noTasks: "No tickets yet.",
    overview: "Overview",
    projectConfig: "Project settings",
    ticketCode: "Ticket code",
    ticketManual: "Typed by PMO, generated when blank",
    ticketAuto: "Always generated",
    ticketPattern: "Ticket code regex (optional)",
    branchPatterns: "Branch patterns",
    environments: "Test environments (comma separated)",
    detectServices: "Detect services",
    addService: "+ Add service",
    servicePath: "Folder in repo",
    kind: "Kind",
    save: "Save",
    saved: "Saved.",
    notFound: "Project not found.",
    language: "Language",
    status: "Details"
  }
};

const KEY = "devmap.lang";

function readLang() {
  try {
    return localStorage.getItem(KEY) === "en" ? "en" : "vi";
  } catch {
    return "vi";
  }
}

const lang = readLang();
document.documentElement.lang = lang;

export function t(key, vars = {}) {
  const text = DICT[lang][key] ?? key;
  return text.replace(/\{(\w+)\}/g, (_, k) => vars[k] ?? "");
}

export function applyI18n(root = document) {
  root.querySelectorAll("[data-i18n]").forEach((node) => { node.textContent = t(node.dataset.i18n); });
}

export function mountLangSwitch(select) {
  select.replaceChildren(new Option("Tiếng Việt", "vi"), new Option("English", "en"));
  select.value = lang;
  select.addEventListener("change", () => {
    try {
      localStorage.setItem(KEY, select.value);
    } catch {
      // storage blocked: language stays for this page only
    }
    location.reload();
  });
}
