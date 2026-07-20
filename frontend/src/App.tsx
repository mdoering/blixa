import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from './auth/LoginPage';
import RequireAuth from './auth/RequireAuth';
import AppLayout from './components/AppLayout';
import PublicLayout from './components/PublicLayout';
import LandingPage from './pages/LandingPage';
import PublicProjectPage from './pages/PublicProjectPage';
import ProjectListPage from './projects/ProjectListPage';
import ProjectLayout from './projects/ProjectLayout';
import ProjectMetadataPage from './projects/ProjectMetadataPage';
import MembersPage from './projects/MembersPage';
import TreePage from './tree/TreePage';
import NameSearchPage from './names/NameSearchPage';
import IssuesPage from './issues/IssuesPage';
import HistoryPage from './history/HistoryPage';
import ReferencesPage from './references/ReferencesPage';
import DiscussionsPage from './discussions/DiscussionsPage';
import CurrentWorkPage from './lock/CurrentWorkPage';

export default function App() {
  return (
    <Routes>
      {/* /login is a backend path (Spring OAuth2 callback + local-login POST, proxied to the API in
          both dev and prod), so the SPA login page lives at /signin to avoid being shadowed. */}
      <Route path="/signin" element={<LoginPage />} />
      <Route element={<PublicLayout />}>
        <Route index element={<LandingPage />} />
        <Route path="p/:idOrAlias" element={<PublicProjectPage />} />
      </Route>
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route path="projects" element={<ProjectListPage />} />
          <Route path="projects/:projectId" element={<ProjectLayout />}>
            <Route index element={<Navigate to="metadata" replace />} />
            <Route path="tree" element={<TreePage />} />
            <Route path="names" element={<NameSearchPage />} />
            <Route path="references" element={<ReferencesPage />} />
            <Route path="issues" element={<IssuesPage />} />
            <Route path="discussions" element={<DiscussionsPage />} />
            <Route path="history" element={<HistoryPage />} />
            <Route path="activity" element={<CurrentWorkPage />} />
            <Route path="metadata" element={<ProjectMetadataPage />} />
            <Route path="members" element={<MembersPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
