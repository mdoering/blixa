import { Navigate, Route, Routes } from 'react-router-dom';
import LoginPage from './auth/LoginPage';
import RequireAuth from './auth/RequireAuth';
import AppLayout from './components/AppLayout';
import ProjectListPage from './projects/ProjectListPage';
import ProjectLayout from './projects/ProjectLayout';
import ProjectMetadataPage from './projects/ProjectMetadataPage';
import MembersPage from './projects/MembersPage';
import TreePage from './tree/TreePage';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth />}>
        <Route element={<AppLayout />}>
          <Route index element={<ProjectListPage />} />
          <Route path="projects/:projectId" element={<ProjectLayout />}>
            <Route index element={<Navigate to="metadata" replace />} />
            <Route path="tree" element={<TreePage />} />
            <Route path="metadata" element={<ProjectMetadataPage />} />
            <Route path="members" element={<MembersPage />} />
          </Route>
        </Route>
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
